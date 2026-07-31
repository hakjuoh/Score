package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Owns trajectory rows and realtime events that describe user-visible interactions. */
final class AiTrajectoryInteractions {

    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final AiContextBudget contextBudget;
    private final AtomicLong estimatedInputFloor;
    private final AtomicLong eventSequence;
    private final AiTrajectoryEventWriter eventWriter;
    private final BooleanSupplier sealed;
    private String lastGuideContent;

    AiTrajectoryInteractions(String requestId, String modelName, String reasoningEffort,
                             AiContextBudget contextBudget, AtomicLong estimatedInputFloor,
                             AtomicLong eventSequence, AiTrajectoryEventWriter eventWriter,
                             BooleanSupplier sealed) {
        this.requestId = requestId;
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.contextBudget = contextBudget;
        this.estimatedInputFloor = estimatedInputFloor;
        this.eventSequence = eventSequence;
        this.eventWriter = eventWriter;
        this.sealed = sealed;
    }

    void userMessage(String content, Map<String, Object> metadata) {
        persist(new AiChatTrajectoryStep(
                        requestId, "user", "user", "visible",
                        Objects.requireNonNullElse(content, ""), null, modelName,
                        reasoningEffort, null, null, null,
                        trace(metadata), null, null, null),
                AiExecutionEvent.detail("user_message_recorded", "", Map.of()), false);
    }

    void assistantMessage(String content, Map<String, Object> metadata) {
        persist(new AiChatTrajectoryStep(
                        requestId, "agent", "assistant", "visible",
                        Objects.requireNonNullElse(content, ""), null, modelName,
                        reasoningEffort, null, null, null,
                        trace(metadata), 0, null, null),
                AiExecutionEvent.detail("assistant_message_recorded", "", Map.of()), false);
    }

    void settledFanOutUsage(String fanoutId, String executionKind,
                            List<AiUsageSnapshot> agents) {
        List<AiUsageSnapshot> settled = agents != null
                ? agents.stream().filter(Objects::nonNull).toList() : List.of();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("fanout_prompt_tokens",
                settled.stream().mapToLong(AiUsageSnapshot::promptTokens).sum());
        metrics.put("fanout_completion_tokens",
                settled.stream().mapToLong(AiUsageSnapshot::completionTokens).sum());
        metrics.put("context_input_tokens", estimatedInputFloor.get());
        metrics.put("context_estimated", true);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("fanout_id", Objects.requireNonNullElse(fanoutId, "unknown"));
        extra.put("execution_kind", Objects.requireNonNullElse(executionKind, "multi_agent"));
        extra.put("agents", settled.stream().map(this::usageItem).toList());
        persist(new AiChatTrajectoryStep(
                        requestId, "system", AiTrajectoryRecorder.FANOUT_USAGE_STEP_KIND, "debug",
                        "parallel".equals(executionKind)
                                ? "Parallel workflow usage settled."
                                : "Multi-agent fan-out usage settled.",
                        null, modelName, reasoningEffort, null, null, Map.copyOf(metrics),
                        trace(extra), 0, null, null),
                AiExecutionEvent.detail("fanout_usage_recorded", "", Map.of(
                        "fanout_id", Objects.requireNonNullElse(fanoutId, "unknown"))), false);
        if (contextBudget != null) {
            contextUsage(contextBudget.usage(
                    estimatedInputFloor.get(), true, "fanout_settled"));
        }
    }

    void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        Map<String, Object> lifecycle = new LinkedHashMap<>(
                metadata != null ? metadata : Map.of());
        lifecycle.put("lifecycle_subtype", subtype);
        Map<String, Object> extra = trace(lifecycle);
        persist(new AiChatTrajectoryStep(
                        requestId, "agent", "agent_lifecycle", "debug", content, null,
                        modelName, reasoningEffort, null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail(subtype, content, extra), true);
    }

    boolean guide(String content, Map<String, Object> metadata, boolean deduplicateAdjacent) {
        if (sealed.getAsBoolean() || !StringUtils.hasText(content)) return false;
        String stripped = content.strip();
        if (deduplicateAdjacent && stripped.equals(lastGuideContent)) return false;
        lastGuideContent = stripped;
        Map<String, Object> extra = trace(metadata);
        persist(new AiChatTrajectoryStep(
                        requestId, "agent", "guide", "visible", stripped, null,
                        modelName, reasoningEffort, null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("guide", stripped, extra), true);
        return true;
    }

    void workflowResult(AgentOutput output, Map<String, Object> metadata) {
        Objects.requireNonNull(output, "output");
        if (!output.passedOutputGuardrail(AgentOutputGuardrail.Scope.PUBLIC)) {
            throw new IllegalArgumentException(
                    "Workflow results require PUBLIC output-guardrail evidence.");
        }
        if (sealed.getAsBoolean() || !StringUtils.hasText(output.content())) return;
        String stripped = output.content().strip();
        Map<String, Object> extra = trace(metadata);
        persist(new AiChatTrajectoryStep(
                        requestId, "agent", "workflow_result", "visible", stripped, null,
                        modelName, reasoningEffort, null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("workflow_result", stripped, extra), true);
    }

    void resetGuideDeduplication() {
        lastGuideContent = null;
    }

    void providerRetry(int attempt, int maxAttempts, long delayMillis,
                       String reason, String failureClass, int statusCode) {
        if (sealed.getAsBoolean()) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempt", attempt);
        metadata.put("max_attempts", maxAttempts);
        metadata.put("delay_millis", delayMillis);
        if (StringUtils.hasText(reason)) metadata.put("reason", reason);
        if (StringUtils.hasText(failureClass)) metadata.put("failure_class", failureClass);
        if (statusCode > 0) metadata.put("status_code", statusCode);
        Map<String, Object> extra = trace(metadata);
        String error = StringUtils.hasText(reason)
                ? reason.strip() : "The model provider could not complete the request.";
        persist(new AiChatTrajectoryStep(
                        requestId, "system", "provider_error", "debug", error, null,
                        modelName, reasoningEffort, null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("provider_error", error, extra), true);
        String retry = "The model provider request failed; retrying (attempt "
                + attempt + " of " + maxAttempts + ").";
        persist(new AiChatTrajectoryStep(
                        requestId, "system", "provider_retry", "debug", retry, null,
                        modelName, reasoningEffort, null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("provider_retry", retry, extra), true);
    }

    void progress(String content) {
        if (sealed.getAsBoolean() || !StringUtils.hasText(content)) return;
        persist(new AiChatTrajectoryStep(
                        requestId, "system", "progress", "debug", content, null,
                        null, null, null, null, null,
                        trace(Map.of("event_sequence", eventSequence.incrementAndGet())),
                        0, null, null),
                AiExecutionEvent.progress(content), true);
    }

    void contentDelta(String content) {
        if (!sealed.getAsBoolean() && content != null && !content.isEmpty()) {
            eventWriter.emit(AiExecutionEvent.contentDelta(content));
        }
    }

    void confirmationRequired(AiChangeConfirmationNotice notice) {
        if (sealed.getAsBoolean() || notice == null) return;
        eventWriter.emit(AiExecutionEvent.detail("change_confirmation_required",
                "A change requires explicit approval.", Map.of(
                        "confirmationRequestId", notice.confirmationRequestId(),
                        "status", notice.status(), "expiresAt", notice.expiresAt().toString(),
                        "toolName", notice.toolName(),
                        "argumentsSummary", notice.argumentsSummary())));
    }

    void approvalBatchRequired(AiChangeApprovalBatchNotice notice) {
        if (sealed.getAsBoolean() || notice == null) return;
        List<Map<String, Object>> items = notice.items().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("confirmationRequestId", item.confirmationRequestId());
            value.put("toolName", item.toolName());
            value.put("argumentsSummary", item.argumentsSummary());
            if (StringUtils.hasText(item.agentId())) value.put("agentId", item.agentId());
            if (StringUtils.hasText(item.agentLabel())) value.put("agentLabel", item.agentLabel());
            return Map.copyOf(value);
        }).toList();
        eventWriter.emit(AiExecutionEvent.detail("change_approval_batch_required",
                items.size() == 1 ? "A change requires explicit approval."
                        : items.size() + " changes require explicit approval.",
                Map.of("batchId", notice.batchId(),
                        "expiresAt", notice.expiresAt().toString(),
                        "parallel", notice.parallel(), "items", items)));
    }

    void approvalDecisionAccepted(
            AiChangeApprovalCoordinator.DecisionAcknowledgement acknowledgement) {
        if (sealed.getAsBoolean() || acknowledgement == null) return;
        long approved = acknowledgement.approved();
        long denied = acknowledgement.denied();
        eventWriter.emit(AiExecutionEvent.detail("change_approval_decision_accepted",
                "Approved " + approved + " change" + (approved == 1 ? "" : "s")
                        + " and denied " + denied + ". Continuing the active request.",
                Map.of("batchId", acknowledgement.batchId(),
                        "approved", approved, "denied", denied)));
    }

    void elicitationRequired(AiElicitationNotice notice) {
        if (sealed.getAsBoolean() || notice == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("elicitationId", notice.elicitationId());
        metadata.put("generation", notice.generation());
        metadata.put("expiresAt", notice.expiresAt().toString());
        metadata.put("mode", "form");
        metadata.put("message", notice.message());
        metadata.put("requestedSchema", notice.requestedSchema());
        eventWriter.emit(AiExecutionEvent.detail("elicitation_required",
                "The assistant needs your input before it can continue.", metadata));
    }

    void contextCompacted(String reason, long beforeTokens,
                          AiContextUsageInfo usage, boolean automatic) {
        if (sealed.getAsBoolean()) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", StringUtils.hasText(reason) ? reason : "threshold");
        metadata.put("automatic", automatic);
        metadata.put("beforeInputTokens", Math.max(0L, beforeTokens));
        if (usage != null) metadata.put("contextUsage", usage);
        eventWriter.emit(AiExecutionEvent.detail("context_compacted",
                automatic ? "Conversation context was compacted automatically."
                        : "Conversation context was compacted.", metadata));
    }

    void contextUsage(AiContextUsageInfo usage) {
        if (!sealed.getAsBoolean() && usage != null) {
            eventWriter.emit(AiExecutionEvent.detail(
                    "context_usage", "Context usage updated.", Map.of("contextUsage", usage)));
        }
    }

    private Map<String, Object> usageItem(AiUsageSnapshot usage) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (usage.nodeId() != null) item.put("node_id", usage.nodeId());
        if (usage.agentName() != null) item.put("agent_name", usage.agentName());
        item.put("prompt_tokens", usage.promptTokens());
        item.put("completion_tokens", usage.completionTokens());
        item.put("model_calls", usage.modelCalls());
        return Map.copyOf(item);
    }

    private Map<String, Object> trace(Map<String, Object> metadata) {
        return eventWriter.traceMetadata(metadata);
    }

    private void persist(AiChatTrajectoryStep step, AiExecutionEvent event, boolean realtime) {
        eventWriter.persist(step, event, realtime);
    }
}
