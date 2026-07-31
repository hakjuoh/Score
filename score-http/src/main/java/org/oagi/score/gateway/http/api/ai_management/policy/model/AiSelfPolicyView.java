package org.oagi.score.gateway.http.api.ai_management.policy.model;

/** Minimal effective policy projection exposed to the signed-in AI client. */
public record AiSelfPolicyView(
        boolean enabled,
        boolean multiAgentEnabled,
        int maxAgentsPerRequest,
        Long maxOutputTokensPerCall,
        Long maxTotalTokensPerRequest,
        AiPolicyView.AiQuotaView quota) {

    public static AiSelfPolicyView from(AiPolicyView policy) {
        return new AiSelfPolicyView(policy.enabled(), policy.multiAgentEnabled(),
                policy.maxAgentsPerRequest(), policy.maxOutputTokensPerCall(),
                policy.maxTotalTokensPerRequest(), policy.quota());
    }
}
