package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrajectoryStepAppenderTest {

    @Test
    void persistsCanonicalIdentityAndRunsCompletionAfterTheWrite() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        AtomicBoolean writeCompleted = new AtomicBoolean();
        when(repository.append(eq("conversation-1"), any()))
                .thenAnswer(ignored -> {
                    writeCompleted.set(true);
                    return new AiChatStoredStep(1L, 1L, Instant.now());
                });
        AtomicReference<ExecutionObservation> observed = new AtomicReference<>();
        ExecutionEventPublisher publisher = ExecutionEventPublisher.forListeners(
                List.of(observed::set));
        TrajectoryStepAppender appender = new TrajectoryStepAppender(publisher);
        AtomicBoolean completionSawWrite = new AtomicBoolean();
        ExecutionScope rootScope = new ExecutionScope("request-1", "conversation-1", "alice",
                7L, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", rootScope, Map.of()));

        appender.append(command(repository, requester(), step("request-1"),
                Map.of("outcome", "accepted"),
                () -> completionSawWrite.set(writeCompleted.get()), 7L));

        ArgumentCaptor<AiChatTrajectoryStep> persisted =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), persisted.capture());
        ExecutionObservation event = observed.get();
        assertThat(event.scope().generation()).isEqualTo(7L);
        assertThat(event.attributes()).containsEntry("outcome", "accepted")
                .containsEntry("message_kind", "assistant")
                .containsEntry("source", "agent");
        assertThat(persisted.getValue().extra())
                .containsEntry(ExecutionEventPublisher.EVENT_ID,
                        event.attributes().get(ExecutionEventPublisher.EVENT_ID))
                .containsEntry(ExecutionEventPublisher.EVENT_SEQUENCE, 2L)
                .containsEntry(ExecutionEventPublisher.EVENT_OCCURRED_AT,
                        event.occurredAt().toString());
        assertThat(persisted.getValue().createdAt()).isEqualTo(event.occurredAt());
        assertThat(completionSawWrite).isTrue();
    }

    @Test
    void rejectsMissingRequestIdentityBeforePublishingOrWriting() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ExecutionObserver observer = mock(ExecutionObserver.class);
        TrajectoryStepAppender appender = new TrajectoryStepAppender(observer);

        assertThatIllegalArgumentException().isThrownBy(() -> appender.append(command(
                        repository, requester(), step(" "), Map.of(), () -> { }, -1L)))
                .withMessage("A request ID is required for an AI trajectory event.");

        verifyNoInteractions(repository, observer);
    }

    @Test
    void rollsBackSequenceAndSkipsCompletionWhenRepositoryWriteFails() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenThrow(new IllegalStateException("write failed"))
                .thenReturn(new AiChatStoredStep(2L, 1L, Instant.now()));
        AtomicReference<ExecutionObservation> observed = new AtomicReference<>();
        TrajectoryStepAppender appender = new TrajectoryStepAppender(
                ExecutionEventPublisher.forListeners(List.of(observed::set)));
        AtomicBoolean completed = new AtomicBoolean();

        assertThatThrownBy(() -> appender.append(command(repository, null,
                step("request-2"), Map.of(), () -> completed.set(true), -1L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("write failed");
        appender.append(command(repository, null, step("request-2"), Map.of(), null, -1L));

        assertThat(completed).isFalse();
        assertThat(observed.get().scope().generation()).isZero();
        assertThat(observed.get().attributes())
                .containsEntry(ExecutionEventPublisher.EVENT_SEQUENCE, 1L);
    }

    @Test
    void propagatesCompletionFailureOnlyAfterTheDurableWrite() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(1L, 1L, Instant.now()));
        TrajectoryStepAppender appender = new TrajectoryStepAppender(ExecutionObserver.noop());

        assertThatThrownBy(() -> appender.append(command(repository, requester(),
                step("request-3"), Map.of(), () -> {
                    throw new IllegalStateException("completion failed");
                }, 0L))).isInstanceOf(IllegalStateException.class)
                .hasMessage("completion failed");

        verify(repository).append(eq("conversation-1"), any());
    }

    private static ScoreUser requester() {
        ScoreUser requester = mock(ScoreUser.class);
        when(requester.username()).thenReturn("alice");
        return requester;
    }

    private static AiChatTrajectoryStep step(String requestId) {
        return new AiChatTrajectoryStep(requestId, "agent", "assistant", "visible",
                "response", null, "model", "medium", null, null, null,
                Map.of("existing", true), 0, null, null);
    }

    private static TrajectoryStepAppender.Command command(
            AiChatConversationRepository repository, ScoreUser requester,
            AiChatTrajectoryStep step, Map<String, Object> attributes,
            Runnable afterAppend, long generation) {
        return new TrajectoryStepAppender.Command(repository, requester, "conversation-1", step,
                ExecutionScope.Purpose.USER_RESPONSE, attributes, afterAppend, generation);
    }
}
