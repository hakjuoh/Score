package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiApprovalConfirmationReservationsTest {

    @Test
    void onlyTheOwningWaiterCanReleaseAConfirmation() {
        AiApprovalConfirmationReservations reservations =
                new AiApprovalConfirmationReservations();
        Object owner = new Object();
        reservations.reserve(owner, List.of("confirmation-1"));

        reservations.release(new Object(), List.of("confirmation-1"));
        assertThatThrownBy(() -> reservations.whileUnreserved(
                "confirmation-1", () -> "legacy"))
                .isInstanceOf(IllegalStateException.class);

        reservations.release(owner, List.of("confirmation-1"));
        assertThat(reservations.whileUnreserved(
                "confirmation-1", () -> "legacy")).isEqualTo("legacy");
    }

    @Test
    void failedMultiReservationDoesNotPartiallyClaimIds() {
        AiApprovalConfirmationReservations reservations =
                new AiApprovalConfirmationReservations();
        reservations.reserve(new Object(), List.of("confirmation-taken"));

        assertThatThrownBy(() -> reservations.reserve(
                new Object(), List.of("confirmation-free", "confirmation-taken")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reservations.whileUnreserved(
                "confirmation-free", () -> "available")).isEqualTo("available");
    }

}
