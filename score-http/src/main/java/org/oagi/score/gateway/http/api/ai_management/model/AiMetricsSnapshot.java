package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.Map;

/** Provider metrics and normalized context usage. */
public record AiMetricsSnapshot(
        Map<String, Object> metrics,
        long contextInputTokens,
        boolean estimated) {
}
