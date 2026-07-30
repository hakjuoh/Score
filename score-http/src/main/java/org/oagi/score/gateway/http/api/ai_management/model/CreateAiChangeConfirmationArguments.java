package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;

/**
 * Values required to create a change-confirmation request for an owned conversation.
 *
 * @param guid public confirmation identifier
 * @param requestId request that attempted the guarded change
 * @param toolName guarded tool name
 * @param argumentsDigest canonical tool-argument digest
 * @param expiresAt request expiration time
 * @param createdAt creation time
 */
public record CreateAiChangeConfirmationArguments(
        String guid,
        String requestId,
        String toolName,
        String argumentsDigest,
        Instant expiresAt,
        Instant createdAt) {
}
