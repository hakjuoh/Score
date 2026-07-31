package org.oagi.score.gateway.http.api.ai_management.service;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Serializes ownership of confirmation IDs across coordinated and legacy approval flows. */
final class AiApprovalConfirmationReservations {

    private final Map<String, Object> owners = new LinkedHashMap<>();

    synchronized <T> T whileUnreserved(String confirmationRequestId, Supplier<T> action) {
        if (!StringUtils.hasText(confirmationRequestId) || action == null) {
            throw new IllegalArgumentException("A change confirmation action is incomplete.");
        }
        if (owners.containsKey(confirmationRequestId)) {
            throw new IllegalStateException(
                    "This change confirmation belongs to an active approval request.");
        }
        return action.get();
    }

    synchronized void reserve(Object owner, List<String> confirmationRequestIds) {
        for (String confirmationRequestId : confirmationRequestIds) {
            if (!StringUtils.hasText(confirmationRequestId)
                    || owners.containsKey(confirmationRequestId)) {
                throw new IllegalStateException(
                        "A change confirmation is already owned by an active approval request.");
            }
        }
        confirmationRequestIds.forEach(id -> owners.put(id, owner));
    }

    synchronized void release(Object owner, List<String> confirmationRequestIds) {
        confirmationRequestIds.forEach(id -> owners.remove(id, owner));
    }
}
