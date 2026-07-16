package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;

/**
 * Latest persisted model-context usage measurement for a conversation.
 *
 * @param modelName model that produced the measurement
 * @param inputTokens input or context tokens observed by the provider
 * @param estimated whether the token count is an estimate
 * @param measuredAt time at which the measurement was recorded
 */
public record AiChatLatestUsage(
        String modelName,
        long inputTokens,
        boolean estimated,
        Instant measuredAt) {
}
