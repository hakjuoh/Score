package org.oagi.score.gateway.http.api.ai_management.policy.model;

import java.util.List;
import java.util.Map;

public record AiPolicyUpdate(
        Long expectedVersion,
        boolean aiEnabled,
        AiModelAccessMode modelAccessMode,
        String defaultModelKey,
        List<String> allowedModelKeys,
        Map<String, List<String>> allowedReasoningEfforts,
        boolean multiAgentEnabled,
        int maxAgentsPerRequest,
        int maxActiveRequests,
        Long maxOutputTokensPerCall,
        Long maxTotalTokensPerRequest,
        AiQuotaPeriod quotaPeriod,
        Long quotaTokens) {

    public AiPolicyUpdate {
        modelAccessMode = modelAccessMode != null ? modelAccessMode : AiModelAccessMode.ALL;
        allowedModelKeys = allowedModelKeys != null ? List.copyOf(allowedModelKeys) : List.of();
        allowedReasoningEfforts = allowedReasoningEfforts != null
                ? Map.copyOf(allowedReasoningEfforts) : Map.of();
    }
}
