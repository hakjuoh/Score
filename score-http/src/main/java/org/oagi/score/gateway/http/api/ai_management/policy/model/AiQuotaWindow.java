package org.oagi.score.gateway.http.api.ai_management.policy.model;

import java.time.Instant;

public record AiQuotaWindow(Instant start, Instant end, long limitTokens) {
    public AiQuotaWindow {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("A quota window requires an ordered start and end.");
        }
        if (limitTokens <= 0) {
            throw new IllegalArgumentException("A quota limit must be positive.");
        }
    }
}
