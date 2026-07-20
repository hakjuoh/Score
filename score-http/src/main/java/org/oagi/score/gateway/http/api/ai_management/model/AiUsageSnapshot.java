package org.oagi.score.gateway.http.api.ai_management.model;

/** Model usage observed for one trajectory node. */
public record AiUsageSnapshot(
        String nodeId,
        String agentName,
        long promptTokens,
        long completionTokens,
        long modelCalls) {
}
