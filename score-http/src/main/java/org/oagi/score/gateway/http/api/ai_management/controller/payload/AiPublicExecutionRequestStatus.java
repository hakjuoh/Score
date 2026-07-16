package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.time.Instant;

public record AiPublicExecutionRequestStatus(
        String conversationId,
        String requestId,
        long generation,
        String agentName,
        String status,
        String statusReason,
        Instant deadline,
        int retryCount,
        Instant nextRetryAt,
        Instant lastHeartbeatAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant terminalAt,
        String cancellationRequestId,
        Instant cancellationRequestedAt,
        Instant cancellationDeadline,
        Instant cancellationAcknowledgedAt,
        long lastEventSequence,
        long version) {}
