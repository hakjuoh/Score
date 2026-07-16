package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;

/**
 * Values required to create a mutation-confirmation request for an owned conversation.
 *
 * @param guid public confirmation identifier
 * @param requestId request that attempted the guarded mutation
 * @param toolName guarded tool name
 * @param argumentsDigest canonical tool-argument digest
 * @param expiresAt request expiration time
 * @param createdAt creation time
 */
public record CreateAiMutationConfirmationArguments(
        String guid,
        String requestId,
        String toolName,
        String argumentsDigest,
        Instant expiresAt,
        Instant createdAt) {
}
