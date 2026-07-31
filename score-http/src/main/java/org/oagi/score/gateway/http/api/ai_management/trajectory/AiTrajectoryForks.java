package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Creates isolated or durable child trajectory recorders with shared request ordering. */
final class AiTrajectoryForks {

    @FunctionalInterface
    interface Factory {
        AiTrajectoryRecorder create(String conversationId, Map<String, Object> traceContext,
                                    ExecutionScope executionScope,
                                    AiChatConversationKind conversationKind);
    }

    private final AiChatConversationRepository repository;
    private final String conversationId;
    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final ExecutionScope executionScope;
    private final AiChatConversationKind conversationKind;
    private final AiTrajectoryEventWriter eventWriter;
    private final Factory factory;

    AiTrajectoryForks(AiChatConversationRepository repository, String conversationId,
                      String requestId, String modelName, String reasoningEffort,
                      ExecutionScope executionScope, AiChatConversationKind conversationKind,
                      AiTrajectoryEventWriter eventWriter, Factory factory) {
        this.repository = repository;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.executionScope = executionScope;
        this.conversationKind = conversationKind;
        this.eventWriter = eventWriter;
        this.factory = factory;
    }

    AiTrajectoryRecorder fork(Map<String, Object> namespace) {
        return factory.create(conversationId, eventWriter.traceMetadata(namespace),
                executionScope, conversationKind);
    }

    AiTrajectoryRecorder forkSubagent(String agentId, String assignment,
                                      Map<String, Object> namespace) {
        return forkChild(AiChatConversationKind.SUBAGENT, agentId, assignment, namespace);
    }

    AiTrajectoryRecorder forkParallel(String workerId, String assignment,
                                      Map<String, Object> namespace) {
        return forkChild(AiChatConversationKind.PARALLEL, workerId, assignment, namespace);
    }

    private AiTrajectoryRecorder forkChild(AiChatConversationKind kind, String workerId,
                                           String assignment, Map<String, Object> namespace) {
        String childId = repository.openChild(
                conversationId, requestId, kind, workerId, assignment);
        Map<String, Object> childNamespace = new LinkedHashMap<>(
                namespace != null ? namespace : Map.of());
        childNamespace.put("child_conversation_id", childId);
        childNamespace.put("conversation_kind", kind.name());
        childNamespace.put("execution_kind",
                kind == AiChatConversationKind.PARALLEL ? "parallel" : "multi_agent");
        AiTrajectoryRecorder child = factory.create(childId,
                eventWriter.traceMetadata(childNamespace), childScope(childId), kind);
        child.persistWithoutRealtime(new AiChatTrajectoryStep(
                        requestId, "system", "settings_change", "debug",
                        "Child execution settings initialized.", null, modelName, reasoningEffort,
                        null, null, null, child.traceMetadata(Map.of("agent_id", workerId)),
                        0, null, null),
                AiExecutionEvent.detail("child_settings_recorded", "", Map.of()));
        child.persistWithoutRealtime(new AiChatTrajectoryStep(
                        requestId, "user",
                        kind == AiChatConversationKind.PARALLEL
                                ? "parallel_assignment" : "assignment",
                        "visible", Objects.requireNonNullElse(assignment, ""), null,
                        modelName, reasoningEffort, null, null, null,
                        child.traceMetadata(Map.of("copied_from_parent", true)), 0, true, null),
                AiExecutionEvent.detail("child_assignment_recorded", "", Map.of()));
        return child;
    }

    private ExecutionScope childScope(String childConversationId) {
        if (executionScope == null) return null;
        return new ExecutionScope(executionScope.requestId(), childConversationId,
                executionScope.requesterId(), executionScope.generation(),
                ExecutionScope.Purpose.WORKER, executionScope.guardrailDecisionIds());
    }
}
