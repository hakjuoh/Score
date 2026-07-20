package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Persisted lifecycle state of a one-time mutation confirmation.
 * Grant values are represented only by their digest and are cleared when the
 * confirmation is denied, expired, or consumed.
 *
 * @param id internal confirmation identifier
 * @param guid public confirmation identifier
 * @param status lifecycle status
 * @param toolName guarded tool name
 * @param argumentsDigest digest binding the tool to its approved arguments
 * @param expiresAt confirmation expiration time
 * @param approvedAt approval time
 * @param deniedAt denial time
 * @param expiredAt expiration time
 * @param consumedAt one-time grant consumption time
 * @param grantDigest digest of the active one-time grant
 */
public record AiMutationConfirmationState(
        long id,
        String guid,
        String status,
        String toolName,
        String argumentsDigest,
        Instant expiresAt,
        Instant approvedAt,
        Instant deniedAt,
        Instant expiredAt,
        Instant consumedAt,
        String grantDigest) {

    private static final Set<String> STATUSES =
            Set.of("REQUESTED", "APPROVED", "DENIED", "CONSUMED", "EXPIRED");

    public AiMutationConfirmationState {
        status = normalizeStatus(status);
    }

    public static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return "EXPIRED";
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return STATUSES.contains(normalized) ? normalized : "EXPIRED";
    }

    /**
     * Returns the approved projection of this state.
     */
    public AiMutationConfirmationState withApproved(
            Instant when, String approvedArgumentsDigest) {
        return new AiMutationConfirmationState(
                id, guid, "APPROVED", toolName, approvedArgumentsDigest, expiresAt,
                when, deniedAt, expiredAt, consumedAt, grantDigest);
    }

    /**
     * Returns the denied projection of this state.
     */
    public AiMutationConfirmationState withDenied(Instant when) {
        return new AiMutationConfirmationState(
                id, guid, "DENIED", toolName, argumentsDigest, expiresAt,
                approvedAt, when, expiredAt, consumedAt, null);
    }

    /**
     * Returns the expired projection of this state.
     */
    public AiMutationConfirmationState withExpired(Instant when) {
        return new AiMutationConfirmationState(
                id, guid, "EXPIRED", toolName, argumentsDigest, expiresAt,
                approvedAt, deniedAt, when, consumedAt, null);
    }
}
