package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.oagi.score.gateway.http.common.model.PageResponse;

import java.time.Instant;

public record AiAdminUsageView(AiPolicyView.AiQuotaView quota, int activeRequests,
                               PeriodUsage periodUsage,
                               PageResponse<LedgerEntry> recentCalls) {
    public record PeriodUsage(long chargedTokens, long reservedTokens, int modelCalls,
                              Instant start, Instant end) {
    }

    public record LedgerEntry(String callId, String modelKey, String executionKind,
                              String agentId, long reservedTokens, long chargedTokens,
                              boolean usageComplete, String status, String failureType,
                              Instant reservedAt, Instant settledAt) {
    }
}
