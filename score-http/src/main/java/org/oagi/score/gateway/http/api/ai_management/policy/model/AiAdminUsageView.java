package org.oagi.score.gateway.http.api.ai_management.policy.model;

import java.time.Instant;
import java.util.List;

public record AiAdminUsageView(AiPolicyView.AiQuotaView quota, int activeRequests,
                               List<LedgerEntry> recentCalls) {
    public record LedgerEntry(String callId, String modelKey, String executionKind,
                              String agentId, long reservedTokens, long chargedTokens,
                              boolean usageComplete, String status, String failureType,
                              Instant reservedAt, Instant settledAt) {
    }
}
