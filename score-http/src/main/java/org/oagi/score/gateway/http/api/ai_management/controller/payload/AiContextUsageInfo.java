package org.oagi.score.gateway.http.api.ai_management.controller.payload;

/** Snapshot of the active model context, distinct from cumulative billing usage. */
public record AiContextUsageInfo(String modelName, long currentInputTokens,
                                 long contextWindow, long safeInputLimit,
                                 long remainingTokens, double usedPercent,
                                 boolean estimated, String source) {
}
