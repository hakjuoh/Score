package org.oagi.score.gateway.http.api.ai_management.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatTrajectoryStepTest {

    @Test
    void normalizesUnknownSourceAndVisibilityToSafeApplicationDefaults() {
        AiChatTrajectoryStep step = new AiChatTrajectoryStep(
                "request-1", "future-source", "progress", "future-visibility",
                "Working", null, null, null, null, Map.of(),
                null, null, null, Map.of(), 0, null, Instant.EPOCH);

        assertThat(step.source()).isEqualTo("system");
        assertThat(step.visibility()).isEqualTo("debug");
        assertThat(AiChatTrajectoryStep.normalizeSource(null)).isEqualTo("system");
        assertThat(AiChatTrajectoryStep.normalizeVisibility(null)).isEqualTo("visible");
    }

    @Test
    void rejectsIncompleteSettingsChangeWithoutInventingDefaults() {
        assertThatThrownBy(() -> new AiChatTrajectoryStep(
                "request-1", "system", "settings_change", "debug",
                "Settings changed", null, "model", null, "runtime", Map.of(),
                null, null, null, Map.of(), 0, null, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settings_change", "reasoningEffort", "no safe defaults");
    }
}
