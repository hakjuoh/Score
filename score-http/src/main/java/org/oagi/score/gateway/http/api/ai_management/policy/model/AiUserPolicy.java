package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;

import java.util.Map;
import java.util.Set;

public record AiUserPolicy(
        UserId userId,
        boolean aiEnabled,
        AiModelAccessMode modelAccessMode,
        AiModelId defaultModelId,
        boolean multiAgentEnabled,
        int maxAgentsPerRequest,
        int maxActiveRequests,
        Long maxOutputTokensPerCall,
        Long maxTotalTokensPerRequest,
        AiQuotaPeriod quotaPeriod,
        Long quotaTokens,
        long policyVersion,
        Set<AiModelId> allowedModels,
        Map<AiModelId, Set<String>> allowedReasoningEfforts) {

    public AiUserPolicy {
        allowedModels = allowedModels != null ? Set.copyOf(allowedModels) : Set.of();
        allowedReasoningEfforts = allowedReasoningEfforts != null
                ? allowedReasoningEfforts.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue()))) : Map.of();
    }
}
