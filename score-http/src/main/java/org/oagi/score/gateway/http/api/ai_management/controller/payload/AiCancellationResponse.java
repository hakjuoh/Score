package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.time.Instant;

public record AiCancellationResponse(
        String requestId,
        String conversationId,
        Long generation,
        String cancellationRequestId,
        String effectiveCancellationRequestId,
        String disposition,
        String status,
        boolean acknowledged,
        boolean terminal,
        long lifecycleEventSequence,
        Instant cancellationRequestedAt,
        Instant cancellationAcknowledgedAt,
        Instant cancellationDeadline,
        Instant terminalAt) {}
