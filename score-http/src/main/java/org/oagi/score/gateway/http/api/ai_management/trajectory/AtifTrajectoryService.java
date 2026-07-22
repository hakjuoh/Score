package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AtifNormalizationResult;
import org.oagi.score.gateway.http.api.ai_management.model.AtifStepExport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds strict ATIF v1.7 documents from stored conversation trajectory data. */
@Service
public class AtifTrajectoryService {

    private static final Set<String> STANDARD_METRIC_FIELDS = Set.of(
            "prompt_tokens",
            "completion_tokens",
            "cached_tokens",
            "cost_usd",
            "prompt_token_ids",
            "completion_token_ids",
            "logprobs");

    /**
     * Builds a strict ATIF v1.7 document from stored trajectory data.
     *
     * @param data persistence-neutral root and child trajectory data
     * @param agentVersion connectCenter agent version
     * @param defaultModel model reported for the root agent
     * @return normalized ATIF v1.7 document
     */
    public Map<String, Object> export(AiChatTrajectoryData data,
                                      String agentVersion, String defaultModel) {
        List<Map<String, Object>> embeddedSubagents = new ArrayList<>();
        List<Map<String, Object>> delegationSteps = new ArrayList<>();
        long subagentPromptTokens = 0;
        long subagentCompletionTokens = 0;
        long subagentCachedTokens = 0;
        for (AiChatTrajectoryData.ChildTrajectory child : data.childTrajectories()) {
            if (child.steps().isEmpty()) {
                continue;
            }
            AtifStepExport childExport = exportSteps(child.steps(), List.of());
            Map<String, Object> childExtra = new LinkedHashMap<>();
            childExtra.put("producer", "connectCenter");
            childExtra.put("parent_trajectory_id", data.conversationId());
            putIfPresent(childExtra, "parent_request_id", child.parentRequestId());
            putIfPresent(childExtra, "conversation_kind", child.conversationKind());
            childExtra.put("source_steps", child.steps().size());
            childExtra.put("returned_steps", childExport.steps().size());
            childExtra.put("collapsed_ui_projections", childExport.collapsedUiProjections());
            embeddedSubagents.add(trajectoryDocument(
                    data.conversationId(), child.trajectoryId(),
                    StringUtils.hasText(child.agentId()) ? child.agentId() : "connectCenter-subagent",
                    agentVersion, defaultModel, childExport,
                    "Embedded child execution exported from its durable conversation.",
                    childExtra));
            delegationSteps.add(delegationStep(data.conversationId(), child));
            subagentPromptTokens += childExport.promptTokens();
            subagentCompletionTokens += childExport.completionTokens();
            subagentCachedTokens += childExport.cachedTokens();
        }

        AtifStepExport rootExport = exportSteps(data.steps(), delegationSteps);
        Map<String, Object> rootExtra = new LinkedHashMap<>();
        int sourceSteps = data.steps().size() + data.childTrajectories().stream()
                .mapToInt(child -> child.steps().size()).sum();
        rootExtra.put("producer", "connectCenter");
        rootExtra.put("total_steps", data.totalStoredSteps());
        rootExtra.put("source_steps", sourceSteps);
        rootExtra.put("returned_steps", rootExport.steps().size());
        rootExtra.put("collapsed_ui_projections", rootExport.collapsedUiProjections());
        rootExtra.put("embedded_subagent_count", embeddedSubagents.size());
        rootExtra.put("truncated", data.truncated());
        Map<String, Object> trajectory = trajectoryDocument(
                data.conversationId(), data.conversationId(), "connectCenter-assistant",
                agentVersion, defaultModel, rootExport,
                "Committed UI projections are collapsed into their matching final model inference when "
                        + "correlation is unambiguous; unmatched deterministic projections remain as steps. "
                        + "Token totals include embedded subagent trajectories.",
                rootExtra);
        @SuppressWarnings("unchecked")
        Map<String, Object> finalMetrics =
                (Map<String, Object>) trajectory.get("final_metrics");
        finalMetrics.put("total_prompt_tokens", rootExport.promptTokens() + subagentPromptTokens);
        finalMetrics.put("total_completion_tokens",
                rootExport.completionTokens() + subagentCompletionTokens);
        finalMetrics.put("total_cached_tokens", rootExport.cachedTokens() + subagentCachedTokens);
        if (!embeddedSubagents.isEmpty()) {
            trajectory.put("subagent_trajectories", List.copyOf(embeddedSubagents));
        }
        return trajectory;
    }

    private AtifStepExport exportSteps(List<AiChatTrajectoryData.Step> rows,
                                      List<Map<String, Object>> additionalSteps) {
        List<Map<String, Object>> sourceSteps = new ArrayList<>(
                rows.size() + additionalSteps.size());
        for (int index = 0; index < rows.size(); index++) {
            AiChatTrajectoryData.Step row = rows.get(index);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step_id", index + 1);
            step.put("timestamp", row.createdAt().toString());
            step.put("source", row.source());
            if (StringUtils.hasText(row.modelName()) && "agent".equals(row.source())) {
                step.put("model_name", row.modelName());
            }
            if (StringUtils.hasText(row.reasoningEffort()) && "agent".equals(row.source())) {
                step.put("reasoning_effort", row.reasoningEffort());
            }
            step.put("message", row.message());
            putIfPresent(step, "reasoning_content", row.reasoningContent());
            putIfPresent(step, "tool_calls", row.toolCalls());
            putIfPresent(step, "observation", row.observation());
            Map<String, Object> extra = new LinkedHashMap<>();
            if (row.extra() != null) {
                extra.putAll(row.extra());
            }
            putIfPresent(extra, "request_id", row.requestId());
            extra.put("message_kind", row.messageKind());
            extra.put("visibility", row.visibility());
            addMetrics(step, extra, row.source(), row.metrics());
            step.put("extra", extra);
            putIfPresent(step, "llm_call_count", row.llmCallCount());
            putIfPresent(step, "is_copied_context", row.copiedContext());
            sourceSteps.add(step);
        }
        sourceSteps.addAll(additionalSteps);
        sourceSteps.sort((left, right) -> Objects.toString(left.get("timestamp"), "")
                .compareTo(Objects.toString(right.get("timestamp"), "")));
        AtifNormalizationResult normalization = normalizeSteps(sourceSteps);

        long promptTokens = 0;
        long completionTokens = 0;
        long cachedTokens = 0;
        for (Map<String, Object> step : normalization.steps()) {
            Object metricsValue = step.get("metrics");
            if (metricsValue instanceof Map<?, ?> metrics) {
                promptTokens += number(metrics.get("prompt_tokens"));
                completionTokens += number(metrics.get("completion_tokens"));
                cachedTokens += number(metrics.get("cached_tokens"));
            }
        }
        return new AtifStepExport(normalization.steps(), promptTokens, completionTokens, cachedTokens,
                normalization.collapsedUiProjections());
    }

    private Map<String, Object> trajectoryDocument(
            String sessionId, String trajectoryId, String agentName,
            String agentVersion, String defaultModel, AtifStepExport export,
            String notes, Map<String, Object> extra) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", agentName);
        agent.put("version", StringUtils.hasText(agentVersion) ? agentVersion : "unknown");
        if (StringUtils.hasText(defaultModel)) {
            agent.put("model_name", defaultModel);
        }

        Map<String, Object> finalMetrics = new LinkedHashMap<>();
        finalMetrics.put("total_prompt_tokens", export.promptTokens());
        finalMetrics.put("total_completion_tokens", export.completionTokens());
        finalMetrics.put("total_cached_tokens", export.cachedTokens());
        finalMetrics.put("total_steps", export.steps().size());

        Map<String, Object> trajectory = new LinkedHashMap<>();
        trajectory.put("schema_version", "ATIF-v1.7");
        trajectory.put("session_id", sessionId);
        trajectory.put("trajectory_id", trajectoryId);
        trajectory.put("agent", agent);
        trajectory.put("steps", export.steps());
        trajectory.put("notes", notes);
        trajectory.put("final_metrics", finalMetrics);
        trajectory.put("extra", extra);
        return trajectory;
    }

    private Map<String, Object> delegationStep(
            String sessionId, AiChatTrajectoryData.ChildTrajectory child) {
        String toolCallId = "delegate-" + child.trajectoryId();
        String assignment = child.steps().stream()
                .filter(row -> "assignment".equals(row.messageKind())
                        || "parallel_assignment".equals(row.messageKind()))
                .map(AiChatTrajectoryData.Step::message)
                .filter(StringUtils::hasText)
                .findFirst().orElse("");
        Map<String, Object> arguments = new LinkedHashMap<>();
        putIfPresent(arguments, "agent_id", child.agentId());
        putIfPresent(arguments, "conversation_kind", child.conversationKind());
        putIfPresent(arguments, "assignment", assignment);
        arguments.put("trajectory_id", child.trajectoryId());

        Map<String, Object> referenceExtra = new LinkedHashMap<>();
        putIfPresent(referenceExtra, "agent_id", child.agentId());
        putIfPresent(referenceExtra, "conversation_kind", child.conversationKind());
        putIfPresent(referenceExtra, "parent_request_id", child.parentRequestId());
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("trajectory_id", child.trajectoryId());
        reference.put("session_id", sessionId);
        reference.put("extra", referenceExtra);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_call_id", toolCallId);
        result.put("content", "Delegated execution trajectory embedded.");
        result.put("subagent_trajectory_ref", List.of(reference));

        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "request_id", child.parentRequestId());
        extra.put("message_kind", "subagent_dispatch");
        extra.put("visibility", "debug");
        putIfPresent(extra, "agent_id", child.agentId());
        putIfPresent(extra, "conversation_kind", child.conversationKind());

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step_id", 0);
        step.put("timestamp", child.steps().getFirst().createdAt().minusNanos(1).toString());
        step.put("source", "agent");
        step.put("message", "Delegated execution to "
                + Objects.requireNonNullElse(child.agentId(), "subagent") + ".");
        step.put("tool_calls", List.of(Map.of(
                "tool_call_id", toolCallId,
                "function_name", "delegate_to_subagent",
                "arguments", arguments)));
        step.put("observation", Map.of("results", List.of(result)));
        step.put("extra", extra);
        step.put("llm_call_count", 0);
        return step;
    }

    Map<String, Object> normalizeMetrics(Map<String, Object> storedMetrics) {
        if (storedMetrics == null || storedMetrics.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, Object> extra = new LinkedHashMap<>();
        Object storedExtra = storedMetrics.get("extra");
        if (storedExtra instanceof Map<?, ?> map) {
            map.forEach((key, value) -> extra.put(Objects.toString(key), value));
        } else if (storedExtra != null) {
            extra.put("producer_extra", storedExtra);
        }
        storedMetrics.forEach((key, value) -> {
            if (STANDARD_METRIC_FIELDS.contains(key)) {
                normalized.put(key, value);
            } else if (!"extra".equals(key)) {
                extra.put(key, value);
            }
        });
        if (!extra.isEmpty()) {
            normalized.put("extra", extra);
        }
        return normalized;
    }

    void addMetrics(Map<String, Object> step, Map<String, Object> extra,
                    String source, Map<String, Object> storedMetrics) {
        Map<String, Object> metrics = normalizeMetrics(storedMetrics);
        if (metrics.isEmpty()) {
            return;
        }
        if ("agent".equals(source)) {
            step.put("metrics", metrics);
        } else {
            extra.put("producer_metrics", metrics);
        }
    }

    AtifNormalizationResult normalizeSteps(List<Map<String, Object>> sourceSteps) {
        List<Map<String, Object>> normalized = new ArrayList<>(sourceSteps.size());
        int collapsed = 0;
        for (Map<String, Object> sourceStep : sourceSteps) {
            Map<String, Object> step = mutableStep(sourceStep);
            if (isAssistantProjection(step)) {
                int modelStepIndex = matchingFinalModelStep(normalized, step);
                if (modelStepIndex >= 0) {
                    normalized.set(modelStepIndex,
                            mergeProjection(normalized.get(modelStepIndex), step));
                    collapsed++;
                    continue;
                }
            }
            normalized.add(step);
        }
        for (int index = 0; index < normalized.size(); index++) {
            normalized.get(index).put("step_id", index + 1);
        }
        return new AtifNormalizationResult(List.copyOf(normalized), collapsed);
    }

    private boolean isAssistantProjection(Map<String, Object> step) {
        Map<String, Object> extra = extra(step);
        return "agent".equals(step.get("source"))
                && "assistant".equals(extra.get("message_kind"))
                && Boolean.TRUE.equals(extra.get("ui_projection"))
                && numberOrMissing(step.get("llm_call_count")) == 0;
    }

    private int matchingFinalModelStep(List<Map<String, Object>> steps,
                                       Map<String, Object> projection) {
        Map<String, Object> projectionExtra = extra(projection);
        Object requestId = projectionExtra.get("request_id");
        for (int index = steps.size() - 1; index >= 0; index--) {
            Map<String, Object> candidate = steps.get(index);
            Map<String, Object> candidateExtra = extra(candidate);
            if (!Objects.equals(requestId, candidateExtra.get("request_id"))) {
                continue;
            }
            if (!"agent".equals(candidate.get("source"))
                    || !"model_call".equals(candidateExtra.get("message_kind"))
                    || (candidateExtra.get("phase") != null
                        && !"assistant".equals(candidateExtra.get("phase")))
                    || numberOrMissing(candidate.get("llm_call_count")) < 1
                    || hasToolCalls(candidate)) {
                continue;
            }
            Object modelMessage = candidate.get("message");
            Object visibleMessage = projection.get("message");
            if (Objects.equals(modelMessage, visibleMessage) || isEmptyMessage(modelMessage)) {
                return index;
            }
            return -1;
        }
        return -1;
    }

    private Map<String, Object> mergeProjection(Map<String, Object> modelStep,
                                                Map<String, Object> projection) {
        Map<String, Object> merged = mutableStep(modelStep);
        if (isEmptyMessage(merged.get("message"))) {
            merged.put("message", projection.get("message"));
        }
        Map<String, Object> mergedExtra = extra(merged);
        Map<String, Object> projectionExtra = extra(projection);
        projectionExtra.forEach((key, value) -> {
            if (!"message_kind".equals(key) && !"visibility".equals(key)) {
                mergedExtra.putIfAbsent(key, value);
            }
        });
        mergedExtra.put("ui_projection", true);
        mergedExtra.put("projection_collapsed", true);
        mergedExtra.put("projection_message_kind", projectionExtra.get("message_kind"));
        mergedExtra.put("visibility", "visible");
        Object projectionTimestamp = projection.get("timestamp");
        if (projectionTimestamp != null) {
            mergedExtra.put("projection_timestamp", projectionTimestamp);
        }
        merged.put("extra", mergedExtra);
        return merged;
    }

    private boolean hasToolCalls(Map<String, Object> step) {
        Object toolCalls = step.get("tool_calls");
        return toolCalls instanceof Collection<?> collection && !collection.isEmpty();
    }

    private boolean isEmptyMessage(Object message) {
        return message instanceof String text && text.isBlank();
    }

    private long numberOrMissing(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> mutableStep(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put("extra", new LinkedHashMap<>(extra(source)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extra(Map<String, Object> step) {
        Object value = step.get("extra");
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

}
