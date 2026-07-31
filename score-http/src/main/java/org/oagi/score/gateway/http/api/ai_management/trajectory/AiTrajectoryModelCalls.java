package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiMetricsSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiObservationAccumulator;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns model-call lifecycle, provider projection, and per-recorder usage accounting. */
final class AiTrajectoryModelCalls {

    @FunctionalInterface
    interface PendingToolSink {
        void enqueue(String name, String callId, Object arguments,
                     AiObservationAccumulator observations);
    }

    private final AiChatConversationRepository repository;
    private final ObjectMapper objectMapper;
    private final String conversationId;
    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final AiContextBudget contextBudget;
    private final AtomicLong estimatedInputFloor;
    private final ExecutionScope executionScope;
    private final ExecutionObserver observer;
    private final AiTrajectoryEventWriter eventWriter;
    private final Function<Object, Object> auditValue;
    private final PendingToolSink pendingToolSink;
    private final Consumer<org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo>
            contextUsage;
    private final BooleanSupplier sealed;
    private final BooleanSupplier usageAccountingSealed;
    private final boolean subagentScope;
    private final Map<String, Object> traceContext;
    private final AtomicLong ownPromptTokens = new AtomicLong();
    private final AtomicLong ownCompletionTokens = new AtomicLong();
    private final AtomicLong ownModelCalls = new AtomicLong();
    private final AtomicLong ownCachedTokens = new AtomicLong();
    private final AtomicLong ownIncompleteModelCalls = new AtomicLong();
    private volatile ProviderPromptTokenNormalizer promptTokenNormalizer;
    private volatile String modelProvider = "unknown";
    private volatile String requestModelName;

    AiTrajectoryModelCalls(AiChatConversationRepository repository, ObjectMapper objectMapper,
                           String conversationId, String requestId, String modelName,
                           String reasoningEffort, AiContextBudget contextBudget,
                           AtomicLong estimatedInputFloor,
                           ProviderPromptTokenNormalizer promptTokenNormalizer,
                           ExecutionScope executionScope, ExecutionObserver observer,
                           AiTrajectoryEventWriter eventWriter, Function<Object, Object> auditValue,
                           PendingToolSink pendingToolSink,
                           Consumer<org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo>
                                   contextUsage,
                           BooleanSupplier sealed, BooleanSupplier usageAccountingSealed,
                           boolean subagentScope, Map<String, Object> traceContext) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.modelName = modelName;
        this.requestModelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.contextBudget = contextBudget;
        this.estimatedInputFloor = estimatedInputFloor;
        this.promptTokenNormalizer = promptTokenNormalizer;
        this.executionScope = executionScope;
        this.observer = observer;
        this.eventWriter = eventWriter;
        this.auditValue = auditValue;
        this.pendingToolSink = pendingToolSink;
        this.contextUsage = contextUsage;
        this.sealed = sealed;
        this.usageAccountingSealed = usageAccountingSealed;
        this.subagentScope = subagentScope;
        this.traceContext = traceContext;
    }

    void useProvider(String providerType) {
        modelProvider = StringUtils.hasText(providerType) ? providerType.strip() : "unknown";
        promptTokenNormalizer = ProviderPromptTokenNormalizer.forProvider(providerType);
    }

    void useProvider(String providerType, String requestModelName) {
        useProvider(providerType);
        this.requestModelName = StringUtils.hasText(requestModelName)
                ? requestModelName.strip() : modelName;
    }

    String provider() {
        return modelProvider;
    }

    String requestModelName() {
        return requestModelName;
    }

    ProviderPromptTokenNormalizer promptTokenNormalizer() {
        return promptTokenNormalizer;
    }

    AiUsageSnapshot usageSnapshot() {
        return new AiUsageSnapshot(stringTrace("node_id"), stringTrace("agent_name"),
                ownPromptTokens.get(), ownCompletionTokens.get(), ownModelCalls.get(),
                ownCachedTokens.get(), ownIncompleteModelCalls.get());
    }

    AiTrajectoryRecorder.ModelCallRecording begin(String phase) {
        if (sealed.getAsBoolean()) return AiTrajectoryRecorder.ModelCallRecording.noop();
        String normalizedPhase = StringUtils.hasText(phase) ? phase : "model";
        Map<String, Object> extra = eventWriter.traceMetadata(Map.of(
                "phase", normalizedPhase, "status", "started"));
        AiChatStoredStep stored = eventWriter.persist(new AiChatTrajectoryStep(
                        requestId, "agent", "model_call", "debug", "", null,
                        modelName, reasoningEffort, null, null, null, extra, 1, null, null),
                modelEvent("started", normalizedPhase), false);
        return new AiTrajectoryRecorder.ModelCallRecording(stored != null ? stored.id() : -1L,
                normalizedPhase, legacy(eventWriter.lastIdentity()));
    }

    AiTrajectoryRecorder.ExecutionEventIdentity recordLegacy(ChatResponse response, String phase,
                                                              boolean streaming) {
        return record(new AiTrajectoryRecorder.ModelCallRecording(-1L,
                StringUtils.hasText(phase) ? phase : "model", null), response, streaming);
    }

    AiTrajectoryRecorder.ExecutionEventIdentity record(
            AiTrajectoryRecorder.ModelCallRecording call, ChatResponse response,
            boolean streaming) {
        if (response == null || response.getResults().isEmpty()) return null;
        AiMetricsSnapshot metricsSnapshot = AiModelResponseProjection.metrics(response, streaming,
                promptTokenNormalizer, estimatedInputFloor, subagentScope);
        if (sealed.getAsBoolean()) {
            if (!usageAccountingSealed.getAsBoolean()) recordUsage(metricsSnapshot);
            return null;
        }
        AiTrajectoryRecorder.ModelCallRecording activeCall = call != null
                ? call : AiTrajectoryRecorder.ModelCallRecording.noop();
        String phase = StringUtils.hasText(activeCall.phase()) ? activeCall.phase() : "model";
        AiModelResponseProjection.Content content = AiModelResponseProjection.content(
                response.getResults(), objectMapper, auditValue);
        Map<String, Object> extra = responseMetadata(response, phase, content);
        AiChatTrajectoryStep completed = new AiChatTrajectoryStep(
                requestId, "agent", "model_call", "debug", "", null,
                StringUtils.hasText(modelName) ? modelName : response.getMetadata().getModel(),
                reasoningEffort, content.auditedToolCalls(), null,
                metricsSnapshot != null ? metricsSnapshot.metrics() : null,
                extra, 1, null, null);
        AiChatStoredStep legacyStored = null;
        AiTrajectoryRecorder.ExecutionEventIdentity completion;
        if (activeCall.stepId() > 0) {
            completion = complete(activeCall, completed, "completed");
        } else {
            legacyStored = eventWriter.persist(completed, modelEvent("completed", phase), false);
            completion = legacy(eventWriter.lastIdentity());
        }
        long observationStepId = activeCall.stepId() > 0
                ? activeCall.stepId() : legacyStored != null ? legacyStored.id() : 0L;
        enqueueTools(content.toolCalls(), new AiObservationAccumulator(
                observationStepId, content.toolCalls()));
        recordUsage(metricsSnapshot);
        if (metricsSnapshot != null && contextBudget != null && !subagentScope) {
            contextUsage.accept(contextBudget.usage(metricsSnapshot.contextInputTokens(),
                    metricsSnapshot.estimated(), metricsSnapshot.estimated()
                            ? "estimate_floor" : "provider"));
        }
        return completion;
    }

    AiTrajectoryRecorder.ExecutionEventIdentity fail(
            AiTrajectoryRecorder.ModelCallRecording call, Throwable failure) {
        if (call == null || call.stepId() <= 0 || sealed.getAsBoolean()) return null;
        Map<String, Object> extra = eventWriter.traceMetadata(Map.of(
                "phase", call.phase(), "status", "failed",
                "failure_type", failure != null ? failure.getClass().getName() : "unknown"));
        AiChatTrajectoryStep failed = new AiChatTrajectoryStep(
                requestId, "agent", "model_call", "debug", "", null, modelName,
                reasoningEffort, null, null, null, extra, 1, null, null);
        return complete(call, failed, "failed");
    }

    private Map<String, Object> responseMetadata(ChatResponse response, String phase,
                                                  AiModelResponseProjection.Content content) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("phase", phase);
        if (StringUtils.hasText(response.getMetadata().getId())) {
            extra.put("provider_response_id", response.getMetadata().getId());
        }
        if (StringUtils.hasText(response.getMetadata().getModel())) {
            extra.put("provider_model", response.getMetadata().getModel());
        }
        if (StringUtils.hasText(content.reasoning())) extra.put("reasoning_present", true);
        if (StringUtils.hasText(content.visible())) extra.put("candidate_content_suppressed", true);
        return new LinkedHashMap<>(eventWriter.traceMetadata(extra));
    }

    private AiTrajectoryRecorder.ExecutionEventIdentity complete(
            AiTrajectoryRecorder.ModelCallRecording call, AiChatTrajectoryStep step,
            String outcome) {
        if (call.stepId() <= 0) return null;
        if (executionScope == null) {
            repository.updateModelCall(conversationId, call.stepId(), step);
            return null;
        }
        var identity = new java.util.concurrent.atomic.AtomicReference<ExecutionEventIdentity>();
        ExecutionObservation observation = AiExecutionLifecycle.from(modelEvent(outcome, call.phase()))
                .observation(executionScope, Instant.now());
        observer.publish(observation, event -> {
            ExecutionEventIdentity terminal = ExecutionEventIdentity.find(event).orElse(null);
            identity.set(terminal);
            Map<String, Object> extra = new LinkedHashMap<>(
                    step.extra() != null ? step.extra() : Map.of());
            if (call.started() != null) call.started().canonical().putAttributes(extra);
            if (terminal != null) {
                extra.put("score.event.end.id", terminal.eventId());
                extra.put("score.event.end.sequence", terminal.sequence());
                extra.put("score.event.end.occurred_at", terminal.occurredAt().toString());
            }
            repository.updateModelCall(conversationId, call.stepId(), copyWithExtra(step, extra));
        });
        return legacy(identity.get());
    }

    private AiChatTrajectoryStep copyWithExtra(AiChatTrajectoryStep step,
                                                Map<String, Object> extra) {
        return new AiChatTrajectoryStep(step.requestId(), step.source(), step.messageKind(),
                step.visibility(), step.message(), step.reasoningContent(), step.modelName(),
                step.reasoningEffort(), step.toolCalls(), step.observation(), step.metrics(),
                Map.copyOf(extra), step.llmCallCount(), step.isCopiedContext(), step.createdAt());
    }

    private void enqueueTools(List<Map<String, Object>> toolCalls,
                              AiObservationAccumulator observations) {
        for (Map<String, Object> toolCall : toolCalls) {
            String name = Objects.toString(toolCall.get("function_name"), "tool");
            String callId = Objects.toString(
                    toolCall.get("tool_call_id"), UUID.randomUUID().toString());
            pendingToolSink.enqueue(name, callId, toolCall.get("arguments"), observations);
        }
    }

    private void recordUsage(AiMetricsSnapshot metrics) {
        if (metrics == null) return;
        ownModelCalls.incrementAndGet();
        ownPromptTokens.addAndGet(longMetric(metrics.metrics().get("prompt_tokens")));
        ownCompletionTokens.addAndGet(longMetric(metrics.metrics().get("completion_tokens")));
        ownCachedTokens.addAndGet(longMetric(metrics.metrics().get("cached_tokens")));
        if (!Boolean.TRUE.equals(metrics.metrics().get("prompt_tokens_complete"))
                || !(metrics.metrics().get("completion_tokens") instanceof Number)) {
            ownIncompleteModelCalls.incrementAndGet();
        }
    }

    private AiExecutionEvent modelEvent(String outcome, String phase) {
        return AiExecutionEvent.detail("model_call_" + outcome, "", Map.of(
                "phase", phase,
                "model", Objects.requireNonNullElse(requestModelName, "unknown"),
                "provider", Objects.requireNonNullElse(modelProvider, "unknown")));
    }

    private long longMetric(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private String stringTrace(String key) {
        return traceContext.get(key) != null ? traceContext.get(key).toString() : null;
    }

    private AiTrajectoryRecorder.ExecutionEventIdentity legacy(ExecutionEventIdentity identity) {
        return identity != null ? new AiTrajectoryRecorder.ExecutionEventIdentity(
                identity.eventId(), identity.sequence(), identity.occurredAt()) : null;
    }
}
