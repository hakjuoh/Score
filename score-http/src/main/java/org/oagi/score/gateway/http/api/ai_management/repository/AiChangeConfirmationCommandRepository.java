package org.oagi.score.gateway.http.api.ai_management.repository;

import org.oagi.score.gateway.http.api.ai_management.model.CreateAiChangeConfirmationArguments;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationId;

import java.time.Instant;

/**
 * Write-side persistence contract for change-confirmation lifecycle transitions.
 */
public interface AiChangeConfirmationCommandRepository {

    /**
     * Creates a confirmation only when the repository's requester owns the target conversation.
     *
     * @param conversationId public conversation identifier
     * @param arguments creation values
     * @return {@code true} when exactly one confirmation was created
     */
    boolean create(String conversationId,
                   CreateAiChangeConfirmationArguments arguments);

    /**
     * Marks a confirmation expired and clears its active grant digest.
     */
    boolean markExpired(AiChangeConfirmationId confirmationId, Instant expiredAt);

    /**
     * Approves a requested confirmation and binds the one-time grant to its arguments.
     */
    boolean approve(AiChangeConfirmationId confirmationId, String grantDigest, Instant approvedAt,
                    Instant grantExpiresAt, String argumentsDigest);

    /**
     * Denies a requested or approved confirmation and clears its active grant digest.
     */
    boolean deny(AiChangeConfirmationId confirmationId, Instant deniedAt);

    /**
     * Atomically consumes an approved one-time grant.
     */
    boolean consume(AiChangeConfirmationId confirmationId, Instant consumedAt);
}
