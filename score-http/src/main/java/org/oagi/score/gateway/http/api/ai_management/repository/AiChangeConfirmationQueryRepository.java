package org.oagi.score.gateway.http.api.ai_management.repository;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationState;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-side persistence contract for change-confirmation lifecycle state.
 * Implementations must preserve ownership checks and acquire row locks for
 * methods ending in {@code ForUpdate}.
 */
public interface AiChangeConfirmationQueryRepository {

    /** Finds an owner-scoped confirmation without acquiring a transition lock. */
    Optional<AiChangeConfirmationState> findOwned(
            String conversationId,
            String confirmationRequestId);

    /**
     * Finds an owner-scoped confirmation and locks it for a lifecycle transition.
     *
     * @param conversationId public conversation identifier
     * @param confirmationRequestId public confirmation identifier
     * @return locked confirmation state, or empty when it is absent or not owned
     */
    Optional<AiChangeConfirmationState> findOwnedForUpdate(
            String conversationId,
            String confirmationRequestId);

    /**
     * Finds the newest unexpired confirmation that can be reused for an identical request.
     *
     * @param conversationId public conversation identifier
     * @param requestId request that attempted the change
     * @param toolName guarded tool name
     * @param argumentsDigest canonical tool-argument digest
     * @param now expiration comparison time
     * @return locked reusable confirmation state, if one exists
     */
    Optional<AiChangeConfirmationState> findReusableForUpdate(
            String conversationId,
            String requestId,
            String toolName,
            String argumentsDigest,
            Instant now);
}
