package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiTrajectoryEventWriterTest {

    @Test
    void sharesCanonicalIdentityAcrossObservationPersistenceAndRealtime() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation> observed =
                new ArrayList<>();
        ExecutionEventPublisher publisher = ExecutionEventPublisher.forListeners(
                List.of(observed::add));
        List<AiExecutionEvent> realtime = new ArrayList<>();
        AiTrajectoryEventWriter writer = writer(repository, realtime, publisher, Map.of());

        writer.persist(step(Map.of()), AiExecutionEvent.detail(
                "workflow_started", "started", Map.of()), true);

        ArgumentCaptor<AiChatTrajectoryStep> persisted =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), persisted.capture());
        Map<String, Object> durable = persisted.getValue().extra();
        Map<String, Object> live = realtime.getFirst().metadata();
        Map<String, Object> canonical = observed.getFirst().attributes();
        for (String key : List.of(ExecutionEventPublisher.EVENT_ID,
                ExecutionEventPublisher.EVENT_SEQUENCE,
                ExecutionEventPublisher.EVENT_OCCURRED_AT)) {
            assertThat(durable.get(key)).isEqualTo(canonical.get(key));
            assertThat(live.get(key)).isEqualTo(canonical.get(key));
        }
    }

    @Test
    void doesNotDeliverRealtimeWhenDurableAppendFails() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(repository).append(eq("conversation-1"), any());
        List<AiExecutionEvent> realtime = new ArrayList<>();
        AiTrajectoryEventWriter writer = writer(repository, realtime,
                ExecutionEventPublisher.forListeners(List.of()), Map.of());

        assertThatThrownBy(() -> writer.persist(step(Map.of()),
                AiExecutionEvent.detail("workflow_started", "started", Map.of()), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage unavailable");
        assertThat(realtime).isEmpty();
    }

    @Test
    void exposesEveryRealtimeAliasWithoutOverwritingAnExplicitTarget() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> realtime = new ArrayList<>();
        Map<String, Object> trace = Map.ofEntries(
                Map.entry("fanout_id", "fanout"), Map.entry("node_id", "node"),
                Map.entry("parent_node_id", "parent"), Map.entry("agent_name", "agent"),
                Map.entry("agent_role", "role"), Map.entry("task_label", "task"),
                Map.entry("active_verb", "working"), Map.entry("completed_verb", "worked"),
                Map.entry("execution_scope", "worker"),
                Map.entry("child_conversation_id", "child"));
        AiTrajectoryEventWriter writer = writer(repository, realtime,
                ExecutionEventPublisher.forListeners(List.of()), trace);

        writer.persist(step(Map.of()), AiExecutionEvent.detail("workflow_started", "started",
                Map.of("parentNodeId", "explicit-parent")), true);

        assertThat(realtime.getFirst().metadata())
                .containsEntry("fanoutId", "fanout")
                .containsEntry("nodeId", "node")
                .containsEntry("agentId", "node")
                .containsEntry("parentNodeId", "explicit-parent")
                .containsEntry("agentName", "agent")
                .containsEntry("agentRole", "role")
                .containsEntry("taskLabel", "task")
                .containsEntry("activeVerb", "working")
                .containsEntry("completedVerb", "worked")
                .containsEntry("executionScope", "worker")
                .containsEntry("childConversationId", "child");
    }

    private AiTrajectoryEventWriter writer(AiChatConversationRepository repository,
                                           List<AiExecutionEvent> realtime,
                                           ExecutionEventPublisher publisher,
                                           Map<String, Object> trace) {
        ExecutionScope scope = new ExecutionScope(
                "request-1", "conversation-1", "user-1", 0,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        return new AiTrajectoryEventWriter(repository, "conversation-1", "request-1",
                realtime::add, scope, publisher, trace, () -> null, () -> false);
    }

    private AiChatTrajectoryStep step(Map<String, Object> extra) {
        return new AiChatTrajectoryStep("request-1", "agent", "agent_lifecycle", "debug",
                "started", null, "model", null, null, null, null, extra,
                0, null, null);
    }
}
