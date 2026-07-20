package org.oagi.score.gateway.http.api.ai_management.model;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.springframework.util.StringUtils;

/** Configured model context limits and compaction policy. */
public record AiContextBudget(
        String modelName,
        long contextWindow,
        long outputReserveTokens,
        long autoCompactThresholdTokens,
        long emergencyHeadroomTokens,
        long toolOutputTokenLimit,
        boolean providerCompactionEnabled) {

    public long safeInputLimit() {
        return Math.max(1L, contextWindow - outputReserveTokens - emergencyHeadroomTokens);
    }

    public boolean shouldCompact(long inputTokens) {
        return inputTokens >= Math.min(autoCompactThresholdTokens, safeInputLimit());
    }

    public boolean exceedsSafeInput(long inputTokens) {
        return inputTokens >= safeInputLimit();
    }

    public AiContextUsageInfo usage(long inputTokens, boolean estimated, String source) {
        long bounded = Math.max(0L, inputTokens);
        long remaining = Math.max(0L, safeInputLimit() - bounded);
        double percent = safeInputLimit() > 0
                ? Math.min(100.0, bounded * 100.0 / safeInputLimit()) : 100.0;
        return new AiContextUsageInfo(modelName, bounded, contextWindow, safeInputLimit(),
                remaining, Math.round(percent * 10.0) / 10.0, estimated,
                StringUtils.hasText(source) ? source : "estimate");
    }
}
