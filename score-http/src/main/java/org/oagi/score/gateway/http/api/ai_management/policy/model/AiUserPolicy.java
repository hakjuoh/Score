package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.oagi.score.gateway.http.api.account_management.model.UserId;

import java.util.Map;
import java.util.Set;

public record AiUserPolicy(
        UserId userId,
        boolean aiEnabled,
        AiModelAccessMode modelAccessMode,
        Long defaultModelId,
        boolean multiAgentEnabled,
        int maxAgentsPerRequest,
        int maxActiveRequests,
        Long maxOutputTokensPerCall,
        Long maxTotalTokensPerRequest,
        AiQuotaPeriod quotaPeriod,
        Long quotaTokens,
        long policyVersion,
        Set<Long> allowedModels,
        Map<Long, Set<String>> allowedReasoningEfforts) {

    public AiUserPolicy {
        allowedModels = allowedModels != null ? Set.copyOf(allowedModels) : Set.of();
        allowedReasoningEfforts = allowedReasoningEfforts != null
                ? allowedReasoningEfforts.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue()))) : Map.of();
    }
}
