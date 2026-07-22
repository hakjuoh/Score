package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Persistence-neutral conversation trajectory data.
 *
 * @param conversationId public root conversation identifier
 * @param totalStoredSteps total root and child steps before any service-level processing
 * @param truncated whether the loaded data excludes stored steps
 * @param steps root-conversation steps
 * @param childTrajectories independently stored child executions
 */
public record AiChatTrajectoryData(
        String conversationId,
        long totalStoredSteps,
        boolean truncated,
        List<Step> steps,
        List<ChildTrajectory> childTrajectories) {

    /** Stored child execution associated with the root conversation. */
    public record ChildTrajectory(
            String trajectoryId,
            String conversationKind,
            String agentId,
            String parentRequestId,
            List<Step> steps) {
    }

    /** Stored conversation step before service-level transformation. */
    public record Step(
            long sequence,
            String requestId,
            String source,
            String messageKind,
            String visibility,
            String message,
            String reasoningContent,
            String modelName,
            String reasoningEffort,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> observation,
            Map<String, Object> metrics,
            Map<String, Object> extra,
            Integer llmCallCount,
            Boolean copiedContext,
            Instant createdAt) {
    }
}
