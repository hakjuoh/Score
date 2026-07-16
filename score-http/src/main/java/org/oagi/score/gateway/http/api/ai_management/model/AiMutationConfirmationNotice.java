package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;

/**
 * Owner-safe approval notice for one guarded mutation tool invocation.
 *
 * @param confirmationRequestId public confirmation identifier
 * @param status confirmation lifecycle status
 * @param expiresAt time after which the request cannot be approved
 * @param toolName guarded tool name
 * @param argumentsSummary bounded, redacted argument summary
 */
public record AiMutationConfirmationNotice(
        String confirmationRequestId,
        String status,
        Instant expiresAt,
        String toolName,
        String argumentsSummary) {
}
