package org.oagi.score.gateway.http.api.ai_management.policy.model;

public record AiUsageSettlement(
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        boolean complete) {
    public AiUsageSettlement {
        if (promptTokens < 0 || completionTokens < 0 || cachedTokens < 0) {
            throw new IllegalArgumentException("AI usage token counts must not be negative.");
        }
    }
}
