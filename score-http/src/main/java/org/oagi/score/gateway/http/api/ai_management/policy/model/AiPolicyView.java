package org.oagi.score.gateway.http.api.ai_management.policy.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AiPolicyView(
        String userId,
        boolean inherited,
        long policyVersion,
        boolean enabled,
        String modelAccessMode,
        String effectiveDefaultModelKey,
        List<String> effectiveAllowedModelKeys,
        Map<String, List<String>> allowedReasoningEfforts,
        boolean multiAgentEnabled,
        int maxAgentsPerRequest,
        int maxActiveRequests,
        boolean hasActiveRequests,
        Long maxOutputTokensPerCall,
        Long maxTotalTokensPerRequest,
        AiQuotaView quota) {

    public record AiQuotaView(
            String period,
            Long limitTokens,
            long consumedTokens,
            long reservedTokens,
            Long remainingTokens,
            Instant periodStart,
            Instant periodEnd) {
    }
}
