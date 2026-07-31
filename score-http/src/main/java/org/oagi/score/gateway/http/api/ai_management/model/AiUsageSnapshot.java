package org.oagi.score.gateway.http.api.ai_management.model;

/** Model usage observed for one trajectory node. */
public record AiUsageSnapshot(
        String nodeId,
        String agentName,
        long promptTokens,
        long completionTokens,
        long modelCalls,
        long cachedTokens,
        long incompleteModelCalls) {
    public AiUsageSnapshot(String nodeId, String agentName, long promptTokens,
                           long completionTokens, long modelCalls) {
        this(nodeId, agentName, promptTokens, completionTokens, modelCalls, 0L, 0L);
    }
}
