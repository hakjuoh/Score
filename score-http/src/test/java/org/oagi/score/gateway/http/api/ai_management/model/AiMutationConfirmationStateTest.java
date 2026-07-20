package org.oagi.score.gateway.http.api.ai_management.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiMutationConfirmationStateTest {

    @Test
    void treatsUnknownStoredStatusAsExpiredFailClosedState() {
        AiMutationConfirmationState state = new AiMutationConfirmationState(
                1L, "confirmation-1", "future-status", "create_record", "digest",
                Instant.MAX, null, null, null, null, null);

        assertThat(state.status()).isEqualTo("EXPIRED");
        assertThat(AiMutationConfirmationState.normalizeStatus(null)).isEqualTo("EXPIRED");
        assertThat(AiMutationConfirmationState.normalizeStatus("approved")).isEqualTo("APPROVED");
    }
}
