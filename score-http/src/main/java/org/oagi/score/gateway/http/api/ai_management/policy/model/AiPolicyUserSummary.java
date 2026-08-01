package org.oagi.score.gateway.http.api.ai_management.policy.model;

import java.util.List;

public record AiPolicyUserSummary(
        String userId,
        String loginId,
        String name,
        String organization,
        boolean inherited,
        boolean enabled,
        boolean multiAgentEnabled,
        int allowedModelCount,
        List<String> availableModels,
        Long quotaLimitTokens,
        long quotaConsumedTokens,
        long quotaReservedTokens,
        Long quotaRemainingTokens,
        int activeRequests,
        String updaterLoginId,
        java.time.Instant lastUpdatedAt) {
}
