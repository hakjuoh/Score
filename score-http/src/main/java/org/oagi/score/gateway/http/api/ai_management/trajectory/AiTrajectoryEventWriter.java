package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns canonical persistence and realtime delivery for one trajectory conversation. */
final class AiTrajectoryEventWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTrajectoryEventWriter.class);

    private final AiChatConversationRepository repository;
    private final String conversationId;
    private final String requestId;
    private final Consumer<AiExecutionEvent> realtimeEvents;
    private final ExecutionScope executionScope;
    private final ExecutionObserver observer;
    private final Map<String, Object> traceContext;
    private final Supplier<String> activeAgentRunId;
    private final BooleanSupplier sealed;
    private volatile ExecutionEventIdentity lastIdentity;

    AiTrajectoryEventWriter(AiChatConversationRepository repository,
                            String conversationId, String requestId,
                            Consumer<AiExecutionEvent> realtimeEvents,
                            ExecutionScope executionScope, ExecutionObserver observer,
                            Map<String, Object> traceContext,
                            Supplier<String> activeAgentRunId, BooleanSupplier sealed) {
        this.repository = repository;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.realtimeEvents = realtimeEvents != null ? realtimeEvents : ignored -> { };
        this.executionScope = executionScope;
        this.observer = observer != null ? observer : ExecutionObserver.noop();
        this.traceContext = traceContext != null ? Map.copyOf(traceContext) : Map.of();
        this.activeAgentRunId = activeAgentRunId;
        this.sealed = sealed;
    }

    AiChatStoredStep persist(AiChatTrajectoryStep step, AiExecutionEvent event,
                             boolean deliverRealtime) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(event, "event");
        if (executionScope == null) {
            AiChatStoredStep stored = repository.append(conversationId,
                    canonicalStep(step, Instant.now(), Map.of()));
            if (deliverRealtime) emit(event);
            lastIdentity = null;
            return stored;
        }
        var stored = new java.util.concurrent.atomic.AtomicReference<AiChatStoredStep>();
        ExecutionObservation observation = AiExecutionLifecycle.from(event)
                .observation(executionScope, Instant.now());
        observer.publish(observation, published -> {
            lastIdentity = ExecutionEventIdentity.find(published).orElse(null);
            stored.set(repository.append(conversationId,
                    canonicalStep(step, published.occurredAt(), published.attributes())));
        }, deliverRealtime ? published -> deliverRealtime(event, published) : ignored -> { });
        return stored.get();
    }

    ExecutionEventIdentity lastIdentity() {
        return lastIdentity;
    }

    Map<String, Object> traceMetadata(Map<String, Object> metadata) {
        if (traceContext.isEmpty() && (metadata == null || metadata.isEmpty())) return Map.of();
        Map<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) merged.putAll(metadata);
        merged.putAll(traceContext);
        String agentRun = activeAgentRunId.get();
        if (StringUtils.hasText(agentRun)) merged.put("agent_run_id", agentRun);
        return Map.copyOf(merged);
    }

    void emit(AiExecutionEvent event) {
        if (sealed.getAsBoolean()) return;
        if (executionScope != null) {
            try {
                observer.publish(AiExecutionLifecycle.from(event).observation(
                                executionScope, Instant.now()), ignored -> { },
                        published -> deliverRealtime(event, published));
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not observe AI trajectory event {} for request {}",
                        event.subtype(), requestId, failure);
            }
            return;
        }
        deliverRealtime(event, null);
    }

    private AiChatTrajectoryStep canonicalStep(AiChatTrajectoryStep step, Instant occurredAt,
                                                Map<String, Object> eventAttributes) {
        Map<String, Object> extra = new LinkedHashMap<>(
                step.extra() != null ? step.extra() : Map.of());
        ExecutionEventIdentity.copyAttributes(eventAttributes, extra);
        return new AiChatTrajectoryStep(step.requestId(), step.source(), step.messageKind(),
                step.visibility(), step.message(), step.reasoningContent(), step.modelName(),
                step.reasoningEffort(), step.toolCalls(), step.observation(), step.metrics(),
                extra.isEmpty() ? Map.of() : Map.copyOf(extra), step.llmCallCount(),
                step.isCopiedContext(), occurredAt);
    }

    private void deliverRealtime(AiExecutionEvent event, ExecutionObservation canonical) {
        Map<String, Object> metadata = new LinkedHashMap<>(realtimeMetadata(event.metadata()));
        if (canonical != null) ExecutionEventIdentity.copyAttributes(canonical, metadata);
        AiExecutionEvent realtimeEvent = new AiExecutionEvent(
                event.type(), event.subtype(), event.content(), event.toolCallId(),
                event.toolName(), event.toolCallSequence(),
                metadata.isEmpty() ? Map.of() : Map.copyOf(metadata));
        try {
            realtimeEvents.accept(realtimeEvent);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not deliver AI trajectory event {} for request {}",
                    event.subtype(), requestId, failure);
        }
    }

    private Map<String, Object> realtimeMetadata(Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>(traceMetadata(metadata));
        alias(merged, "fanout_id", "fanoutId");
        alias(merged, "node_id", "nodeId");
        alias(merged, "node_id", "agentId");
        alias(merged, "parent_node_id", "parentNodeId");
        alias(merged, "agent_name", "agentName");
        alias(merged, "agent_role", "agentRole");
        alias(merged, "task_label", "taskLabel");
        alias(merged, "active_verb", "activeVerb");
        alias(merged, "completed_verb", "completedVerb");
        alias(merged, "execution_scope", "executionScope");
        alias(merged, "child_conversation_id", "childConversationId");
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private void alias(Map<String, Object> metadata, String source, String target) {
        if (metadata.containsKey(source) && !metadata.containsKey(target)) {
            metadata.put(target, metadata.get(source));
        }
    }
}
