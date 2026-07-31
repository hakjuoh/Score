package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.oagi.score.gateway.http.api.account_management.model.UserId;

import java.util.UUID;

public record AiCallReservation(
        UUID callId,
        String requestId,
        UserId userId,
        long reservedTokens,
        Integer effectiveMaxOutputTokens,
        AiQuotaWindow window) {
}
