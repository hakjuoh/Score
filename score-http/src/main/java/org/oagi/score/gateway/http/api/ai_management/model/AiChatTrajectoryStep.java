package org.oagi.score.gateway.http.api.ai_management.model;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete, persistence-ready representation of one AI conversation trajectory step.
 * The record retains model, runtime, tool, observation, and metric data needed for
 * audit replay and ATIF export.
 *
 * @param requestId request that produced the step
 * @param source ATIF source such as {@code system}, {@code user}, or {@code agent}
 * @param messageKind application-specific event kind
 * @param visibility UI visibility of the step
 * @param message user-visible or diagnostic text
 * @param reasoningContent optional model reasoning content
 * @param modelName optional model identifier
 * @param reasoningEffort optional reasoning effort
 * @param runtime optional agent runtime
 * @param runtimeOptions runtime-specific option values
 * @param toolCalls serialized tool calls
 * @param observation tool execution observation
 * @param metrics token and execution metrics
 * @param extra additional trajectory metadata
 * @param llmCallCount number of model calls represented by the step
 * @param isCopiedContext whether the step was copied into model context
 * @param createdAt optional caller-supplied creation time
 */
public record AiChatTrajectoryStep(
        String requestId,
        String source,
        String messageKind,
        String visibility,
        String message,
        String reasoningContent,
        String modelName,
        String reasoningEffort,
        String runtime,
        Map<String, Object> runtimeOptions,
        List<Map<String, Object>> toolCalls,
        Map<String, Object> observation,
        Map<String, Object> metrics,
        Map<String, Object> extra,
        Integer llmCallCount,
        Boolean isCopiedContext,
        Instant createdAt) {

    public AiChatTrajectoryStep {
        source = Objects.requireNonNull(source, "source");
        messageKind = Objects.requireNonNull(messageKind, "messageKind");
        visibility = StringUtils.hasText(visibility) ? visibility : "visible";
        message = Objects.requireNonNullElse(message, "");
        runtimeOptions = runtimeOptions != null ? Map.copyOf(runtimeOptions) : Map.of();
    }

    /**
     * Creates a trajectory step without explicit reasoning-effort or runtime settings.
     */
    public AiChatTrajectoryStep(
            String requestId,
            String source,
            String messageKind,
            String visibility,
            String message,
            String reasoningContent,
            String modelName,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> observation,
            Map<String, Object> metrics,
            Map<String, Object> extra,
            Integer llmCallCount,
            Boolean isCopiedContext,
            Instant createdAt) {
        this(requestId, source, messageKind, visibility, message, reasoningContent, modelName,
                null, null, Map.of(), toolCalls, observation, metrics, extra,
                llmCallCount, isCopiedContext, createdAt);
    }
}
