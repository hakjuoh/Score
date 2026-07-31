package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStepId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTrajectoryModelCallsTest {

    @Test
    void sealedModelCallsRejectLifecyclePersistence() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        Fixture fixture = fixture(repository, true, false, Map.of());

        assertThat(fixture.calls.begin("assistant"))
                .isEqualTo(AiTrajectoryRecorder.ModelCallRecording.noop());
        verify(repository, never()).append(any(), any());
    }

    @Test
    void startedCallPersistsItsNormalizedPhaseAndStoredCorrelation() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(AiChatStepId.from(42L), 1L, Instant.now()));
        Fixture fixture = fixture(repository, false, false, Map.of());

        AiTrajectoryRecorder.ModelCallRecording recording = fixture.calls.begin("  ");

        assertThat(recording.stepId()).isEqualTo(AiChatStepId.from(42L));
        assertThat(recording.phase()).isEqualTo("model");
        var step = org.mockito.ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().extra())
                .containsEntry("phase", "model")
                .containsEntry("status", "started");
    }

    @Test
    void disclosureFenceAllowsOnlyBoundedLateUsageAccounting() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        Fixture fixture = fixture(repository, true, false,
                Map.of("node_id", "worker-1", "agent_name", "researcher"));
        fixture.calls.useProvider("openai");
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("private late candidate"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(7, 3, 10)).build());

        fixture.calls.recordLegacy(response, "assistant", false);
        AiUsageSnapshot bounded = fixture.calls.usageSnapshot();
        fixture.usageAccountingSealed.set(true);
        fixture.calls.recordLegacy(response, "assistant", false);

        assertThat(bounded).isEqualTo(new AiUsageSnapshot(
                "worker-1", "researcher", 7L, 3L, 1L));
        assertThat(fixture.calls.usageSnapshot()).isEqualTo(bounded);
        verify(repository, never()).append(any(), any());
    }

    private Fixture fixture(AiChatConversationRepository repository, boolean sealed,
                            boolean usageSealed, Map<String, Object> traceContext) {
        AtomicBoolean disclosureFence = new AtomicBoolean(sealed);
        AtomicBoolean usageAccountingFence = new AtomicBoolean(usageSealed);
        AiTrajectoryEventWriter writer = new AiTrajectoryEventWriter(
                repository, "conversation-1", "request-1", ignored -> { },
                null, ExecutionObserver.noop(), traceContext, () -> null,
                disclosureFence::get);
        AiTrajectoryModelCalls calls = new AiTrajectoryModelCalls(
                repository, new ObjectMapper(), "conversation-1", "request-1",
                "model", "high", null, new AtomicLong(),
                ProviderPromptTokenNormalizer.forProvider(null), null,
                ExecutionObserver.noop(), writer, value -> value,
                (name, callId, arguments, observations) -> { }, ignored -> { },
                disclosureFence::get, usageAccountingFence::get, false, traceContext);
        return new Fixture(calls, usageAccountingFence);
    }

    private record Fixture(AiTrajectoryModelCalls calls,
                           AtomicBoolean usageAccountingSealed) { }
}
