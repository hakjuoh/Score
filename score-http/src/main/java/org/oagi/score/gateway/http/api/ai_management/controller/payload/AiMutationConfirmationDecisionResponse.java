package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiMutationConfirmationDecisionResponse(
        String confirmationRequestId,
        String conversationId,
        String status,
        String disposition,
        Instant expiresAt,
        Instant approvedAt,
        Instant deniedAt,
        Instant expiredAt,
        Instant consumedAt,
        String confirmationGrant) {}
