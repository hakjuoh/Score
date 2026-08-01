package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeFable5Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeHaiku45Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.Gpt56SolProfile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelProfileSettingsValidatorTest {

    private final Gpt56SolProfile profile = new Gpt56SolProfile();

    @Test
    void acceptsSmallerLimitsAndASubsetOfAllowedEfforts() {
        AiModelCatalogUpdate input = update(900_000L, false,
                List.of(new AiModelCatalogUpdate.ReasoningEffortUpdate(
                        "medium", true, 0)));

        var configured = AiModelProfileSettingsValidator.validate(profile, input);

        assertThat(configured).singleElement().satisfies(effort -> {
            assertThat(effort.name()).isEqualTo("medium");
            assertThat(effort.displayName()).isEqualTo("Medium");
            assertThat(effort.defaultEffort()).isTrue();
        });
    }

    @Test
    void rejectsValuesBeyondProfileLimitsAndUnsupportedCapabilities() {
        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(
                profile, update(1_050_001L, false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the range");

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(
                profile, update(900_000L, true, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature is not supported");
    }

    @Test
    void rejectsOutputAndCompactionLimitsThatReachTheSelectedContextWindow() {
        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(
                profile, update(100_000L, 70_000L, false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max output tokens must be smaller");

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(
                profile, update(900_000L, 900_000L, false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compaction threshold");
    }

    @Test
    void rejectsToolOutputLimitAboveTheSafeInputBudget() {
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L), profile.getModelKey(),
                true, true, 0, 100_000, 900_000L, 100_000L, 700_000L,
                8_192L, 791_809L, false, null, null, false, null, null,
                true, false, true, false, List.of(), null, List.of());

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(profile, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool limit");
    }

    @Test
    void rejectsAnEffortThatTheProfileDoesNotAllow() {
        var unsupported = new AiModelCatalogUpdate.ReasoningEffortUpdate(
                "ultra", true, 0);

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(
                profile, update(900_000L, false, List.of(unsupported))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed by the model profile");
    }

    @Test
    void usesTheProfileDefaultWhenANullCapabilityComesFromAnOlderPayload() {
        AiModelCatalogUpdate input = update(900_000L, false,
                List.of(new AiModelCatalogUpdate.ReasoningEffortUpdate(
                        "medium", true, 0)));
        input = new AiModelCatalogUpdate(input.expectedVersion(), input.providerId(),
                input.modelKey(), input.enabled(), input.defaultModel(), input.sortOrder(),
                input.maxTokens(), input.contextWindow(), input.outputReserveTokens(),
                input.autoCompactThresholdTokens(), input.emergencyHeadroomTokens(),
                input.toolOutputTokenLimit(), input.providerCompactionEnabled(),
                input.temperature(), input.thinkingBudgetTokens(), input.adaptiveThinking(),
                input.outputEffort(), input.cacheStrategy(), null,
                input.outputEffortSupported(), input.verbositySupported(),
                input.temperatureSupported(), input.thinkingModes(), input.defaultThinking(),
                input.reasoningEfforts());

        assertThat(AiModelProfileSettingsValidator.validate(profile, input))
                .extracting(effort -> effort.name()).containsExactly("medium");
    }

    @Test
    void rejectsThinkingBudgetAtOrAboveTheConfiguredOutputLimit() {
        var haiku = new ClaudeHaiku45Profile();
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L), haiku.getModelKey(), true, false, 0,
                2_000, 100_000L, 10_000L, 80_000L, 4_096L, 16_000L,
                false, null, 4_096, false, null, "conversation-history",
                null, false, null, false, List.of("disabled"), "disabled", List.of());

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(haiku, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("smaller than configured max output");
    }

    @Test
    void validatesTheEffectiveThinkingDefaultAgainstConfiguredOutputLimit() {
        var haiku = new ClaudeHaiku45Profile();
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L), haiku.getModelKey(), true, false, 0,
                2_000, 100_000L, 10_000L, 80_000L, 4_096L, 16_000L,
                false, null, null, false, null, "conversation-history",
                null, false, null, false, List.of("enabled", "disabled"), "enabled", List.of());

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(haiku, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("smaller than configured max output");
    }

    @Test
    void requiresAdaptiveFlagAndModeToChangeTogether() {
        var claude = new ClaudeFable5Profile();
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L), claude.getModelKey(), true, false, 0,
                100_000, 900_000L, 100_000L, 700_000L, 8_192L, 32_000L,
                false, null, null, false, "high", "conversation-history",
                null, true, null, false, List.of("adaptive"), "adaptive", List.of());

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(claude, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be enabled together");
    }

    @Test
    void rejectsDisablingProviderEnforcedAdaptiveThinking() {
        var claude = new ClaudeFable5Profile();
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L), claude.getModelKey(), true, false, 0,
                100_000, 900_000L, 100_000L, 700_000L, 8_192L, 32_000L,
                false, null, null, false, "high", "conversation-history",
                null, true, null, false, List.of(), null, List.of());

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(claude, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provider-enforced thinking modes");
    }

    @Test
    void rejectsFixedThinkingWithoutAnExplicitModeAndDefault() {
        var haiku = new ClaudeHaiku45Profile();
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L), haiku.getModelKey(), true, false, 0,
                64_000, 200_000L, 64_000L, 120_000L, 8_192L, 32_000L,
                false, null, 4_096, false, null, "conversation-history",
                null, false, null, false, List.of(), null, List.of());

        assertThatThrownBy(() -> AiModelProfileSettingsValidator.validate(haiku, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fixed thinking requires");
    }

    private AiModelCatalogUpdate update(long contextWindow, boolean temperatureSupported,
                                        List<AiModelCatalogUpdate.ReasoningEffortUpdate> efforts) {
        return update(contextWindow, 700_000L, temperatureSupported, efforts);
    }

    private AiModelCatalogUpdate update(long contextWindow, long autoCompactThreshold,
                                        boolean temperatureSupported,
                                        List<AiModelCatalogUpdate.ReasoningEffortUpdate> efforts) {
        return new AiModelCatalogUpdate(1L, AiProviderId.from(1L), profile.getModelKey(), true, true, 0,
                100_000, contextWindow, 100_000L, autoCompactThreshold, 8_192L, 32_000L,
                false, null, null, false, null, null, true, false, true,
                temperatureSupported, List.of(), null, efforts);
    }
}
