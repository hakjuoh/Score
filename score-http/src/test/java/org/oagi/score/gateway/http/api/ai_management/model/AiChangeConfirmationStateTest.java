package org.oagi.score.gateway.http.api.ai_management.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiChangeConfirmationStateTest {

    @Test
    void treatsUnknownStoredStatusAsExpiredFailClosedState() {
        AiChangeConfirmationState state = new AiChangeConfirmationState(
                1L, "confirmation-1", "request-1", "future-status", "create_record", "digest",
                Instant.MAX, null, null, null, null, null);

        assertThat(state.status()).isEqualTo("EXPIRED");
        assertThat(AiChangeConfirmationState.normalizeStatus(null)).isEqualTo("EXPIRED");
        assertThat(AiChangeConfirmationState.normalizeStatus("approved")).isEqualTo("APPROVED");
    }
}
