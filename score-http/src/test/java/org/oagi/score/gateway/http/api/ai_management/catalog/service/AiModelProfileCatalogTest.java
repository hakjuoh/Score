package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeFable5Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeHaiku45Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeOpus5Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeSonnet5Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.Gpt56LunaProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.Gpt56SolProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.Gpt56TerraProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.CacheModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AdaptiveThinkingModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.CapabilityConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.DecimalConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.FixedThinkingModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ModelTokenConstraints;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.NumericConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.OutputEffortModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ProviderCompactionModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffortModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningOptionsModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.TemperatureModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ThinkingModesModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.VerbosityModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelProfileSettingsResolver;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelProfileCatalogTest {

    @Test
    void exposesEachSupportedModelThroughItsOwnProfileClass() {
        assertThat(AiModelProfileCatalog.modelsFor("anthropic"))
                .hasExactlyElementsOfTypes(
                        ClaudeFable5Profile.class, ClaudeOpus5Profile.class,
                        ClaudeSonnet5Profile.class, ClaudeHaiku45Profile.class);
        assertThat(AiModelProfileCatalog.modelsFor("openai"))
                .hasExactlyElementsOfTypes(
                        Gpt56SolProfile.class, Gpt56TerraProfile.class, Gpt56LunaProfile.class);
        assertThat(AiModelProfileCatalog.modelsFor("azure-openai"))
                .extracting(profile -> profile.getModelKey())
                .containsExactly("gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna");
    }

    @Test
    void haikuUsesFixedThinkingWithoutReasoningEffort() {
        var haiku = new ClaudeHaiku45Profile();
        var view = AiModelProfileView.from(haiku);

        assertThat(view.thinkingBudgetTokens()).isEqualTo(4096);
        assertThat(view.adaptiveThinking()).isFalse();
        assertThat(haiku.getThinkingModes()).containsExactly("enabled", "disabled");
        assertThat(view.reasoningEfforts()).isEmpty();
    }

    @Test
    void currentOpenAiProfilesUsePublishedGpt56LimitsAndEfforts() {
        var sol = new Gpt56SolProfile();
        var view = AiModelProfileView.from(sol);

        assertThat(view.contextWindow()).isEqualTo(1_050_000L);
        assertThat(view.maxTokens()).isEqualTo(128_000);
        assertThat(sol.getReasoningEfforts())
                .extracting(effort -> effort.name())
                .containsExactly("disabled", "low", "medium", "high", "xhigh", "max");
        assertThat(sol.getReasoningEfforts()).filteredOn(effort -> effort.defaultEffort())
                .extracting(effort -> effort.name()).containsExactly("medium");
        assertThat(view.capabilityConstraints().providerCompaction().supported()).isTrue();
        assertThat(view.capabilityConstraints().providerCompaction().defaultEnabled()).isFalse();
    }

    @Test
    void baseProfileContainsOnlyCommonModelElements() {
        assertThat(AiModelProfile.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("getProviderType", "getModelKey", "getProviderModelName",
                        "getDisplayName", "getDescription", "getTokenConstraints");
        assertThat(new ClaudeHaiku45Profile()).isInstanceOf(FixedThinkingModelProfile.class);
        assertThat(new ClaudeHaiku45Profile()).isInstanceOf(CacheModelProfile.class);
        assertThat(new ClaudeHaiku45Profile())
                .isNotInstanceOf(AdaptiveThinkingModelProfile.class)
                .isNotInstanceOf(ReasoningEffortModelProfile.class);
    }

    @Test
    void everyRegisteredProfileExposesACoherentCapabilityContract() {
        Stream.of("anthropic", "openai").flatMap(provider ->
                AiModelProfileCatalog.modelsFor(provider).stream()).forEach(profile -> {
            var view = AiModelProfileView.from(profile);
            assertThat(profile.getProviderType()).isNotBlank();
            assertThat(profile.getModelKey()).isNotBlank();
            assertThat(profile.getProviderModelName()).isNotBlank();
            assertThat(profile.getDisplayName()).isNotBlank();
            assertThat(view.capabilityConstraints()).satisfies(capabilities -> {
                assertCapabilityCoherent(capabilities.reasoningOptions());
                assertCapabilityCoherent(capabilities.outputEffort());
                assertCapabilityCoherent(capabilities.verbosity());
                assertCapabilityCoherent(capabilities.temperature());
                assertCapabilityCoherent(capabilities.adaptiveThinking());
                assertCapabilityCoherent(capabilities.providerCompaction());
            });
            if (profile instanceof ThinkingModesModelProfile thinking) {
                assertThat(thinking.getThinkingModes()).isNotEmpty().doesNotHaveDuplicates();
                assertThat(thinking.getDefaultThinking()).isIn(thinking.getThinkingModes());
            }
            if (profile instanceof FixedThinkingModelProfile fixed) {
                assertThat(fixed.getThinkingBudgetConstraint().maximum()).isNotNull();
            }
            if (profile instanceof AdaptiveThinkingModelProfile adaptive) {
                assertThat(adaptive.getAdaptiveThinkingCapability().supported()).isTrue();
            }
            if (profile instanceof OutputEffortModelProfile output) {
                assertThat(output.getOutputEfforts()).isNotEmpty();
                assertThat(output.getDefaultOutputEffort())
                        .isIn(output.getOutputEfforts().stream().map(effort -> effort.name()).toList());
            }
            assertThat(view.reasoningEfforts()).satisfies(efforts -> {
                assertThat(efforts).extracting(effort -> effort.name()).doesNotHaveDuplicates();
                assertThat(efforts).extracting(effort -> effort.sortOrder()).doesNotHaveDuplicates();
                if (!efforts.isEmpty()) {
                    assertThat(efforts).filteredOn(effort -> effort.defaultEffort()).hasSize(1);
                }
            });
        });
    }

    @Test
    void providerFamiliesExposeOnlyTheirExactCapabilityContracts() {
        AiModelProfileCatalog.modelsFor("openai").forEach(profile -> assertThat(profile)
                .isInstanceOf(ReasoningOptionsModelProfile.class)
                .isInstanceOf(ReasoningEffortModelProfile.class)
                .isInstanceOf(VerbosityModelProfile.class)
                .isInstanceOf(ProviderCompactionModelProfile.class)
                .isNotInstanceOf(OutputEffortModelProfile.class)
                .isNotInstanceOf(CacheModelProfile.class)
                .isNotInstanceOf(TemperatureModelProfile.class)
                .isNotInstanceOf(ThinkingModesModelProfile.class)
                .isNotInstanceOf(AdaptiveThinkingModelProfile.class)
                .isNotInstanceOf(FixedThinkingModelProfile.class));
        AiModelProfileCatalog.modelsFor("anthropic").stream()
                .filter(profile -> !(profile instanceof ClaudeHaiku45Profile))
                .forEach(profile -> assertThat(profile)
                        .isInstanceOf(AdaptiveThinkingModelProfile.class)
                        .isInstanceOf(OutputEffortModelProfile.class)
                        .isInstanceOf(CacheModelProfile.class)
                        .isNotInstanceOf(ReasoningOptionsModelProfile.class)
                        .isNotInstanceOf(ReasoningEffortModelProfile.class)
                        .isNotInstanceOf(VerbosityModelProfile.class)
                        .isNotInstanceOf(ProviderCompactionModelProfile.class)
                        .isNotInstanceOf(TemperatureModelProfile.class)
                        .isNotInstanceOf(FixedThinkingModelProfile.class));
        AiModelProfileCatalog.modelsFor("anthropic").stream()
                .filter(ClaudeHaiku45Profile.class::isInstance)
                .forEach(profile -> assertThat(profile)
                        .isInstanceOf(FixedThinkingModelProfile.class)
                        .isInstanceOf(ThinkingModesModelProfile.class)
                        .isInstanceOf(CacheModelProfile.class)
                        .isNotInstanceOf(AdaptiveThinkingModelProfile.class)
                        .isNotInstanceOf(OutputEffortModelProfile.class)
                        .isNotInstanceOf(ReasoningOptionsModelProfile.class)
                        .isNotInstanceOf(ReasoningEffortModelProfile.class)
                        .isNotInstanceOf(VerbosityModelProfile.class)
                        .isNotInstanceOf(ProviderCompactionModelProfile.class)
                        .isNotInstanceOf(TemperatureModelProfile.class));
    }

    @Test
    void rejectsAmbiguousEffortProfilesAndInvalidConstraintStates() {
        assertThatThrownBy(() -> AiModelProfileView.from(new AmbiguousEffortProfile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> new CapabilityConstraint(false, true))
                .isInstanceOf(IllegalArgumentException.class);
        Stream.<Runnable>of(
                () -> new NumericConstraint(5L, null, null, true),
                () -> new NumericConstraint(null, 1L, null, true),
                () -> new NumericConstraint(null, null, 10L, true),
                () -> new NumericConstraint(null, 10L, 1L, true),
                () -> new NumericConstraint(11L, 1L, 10L, true),
                () -> new NumericConstraint(null, 1L, 10L, false))
                .forEach(invalid -> assertThatThrownBy(invalid::run)
                        .isInstanceOf(IllegalArgumentException.class));
        Stream.<Runnable>of(
                () -> new DecimalConstraint(0.5, null, null, true),
                () -> new DecimalConstraint(null, 0.0, null, true),
                () -> new DecimalConstraint(null, null, 1.0, true),
                () -> new DecimalConstraint(null, 1.0, 0.0, true),
                () -> new DecimalConstraint(1.1, 0.0, 1.0, true),
                () -> new DecimalConstraint(null, 0.0, 1.0, false),
                () -> new DecimalConstraint(Double.NaN, 0.0, 1.0, true),
                () -> new DecimalConstraint(null, Double.NEGATIVE_INFINITY, 1.0, true),
                () -> new DecimalConstraint(null, 0.0, Double.POSITIVE_INFINITY, true))
                .forEach(invalid -> assertThatThrownBy(invalid::run)
                        .isInstanceOf(IllegalArgumentException.class));
        Stream.of("contextWindow", "maxOutputTokens", "outputReserveTokens",
                        "autoCompactThresholdTokens", "emergencyHeadroomTokens",
                        "toolOutputTokenLimit")
                .forEach(missing -> assertThatThrownBy(() -> tokensMissing(missing))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(missing));
    }

    @Test
    void installsProfileConstantsIntoTheBootstrapConfiguration() {
        ScoreAiProperties.Provider anthropic = new ScoreAiProperties.Provider();
        anthropic.setType("anthropic");
        ScoreAiProperties.Provider openAi = new ScoreAiProperties.Provider();
        openAi.setType("azure-openai");
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setProviders(new LinkedHashMap<>(Map.of(
                "azure-foundry", anthropic, "azure-openai", openAi)));

        AiModelProfileCatalog.install(properties);

        assertThat(properties.getModels()).containsOnlyKeys(
                "claude-fable-5", "claude-opus-5", "claude-sonnet-5",
                "claude-haiku-4_5", "gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna");
        assertThat(properties.getModels().get("claude-haiku-4_5").getReasoningEfforts())
                .isEmpty();
        assertThat(properties.getModels().get("gpt-5_6-sol").getReasoningEfforts())
                .extracting(ScoreAiProperties.ReasoningEffort::getName)
                .containsExactly("disabled", "low", "medium", "high", "xhigh", "max");
        assertThat(properties.getModels().get("gpt-5_6-sol").getProvider())
                .isEqualTo("azure-openai");
    }

    @Test
    void replacesUnsupportedLegacyBootstrapModels() {
        ScoreAiProperties.Provider openAi = new ScoreAiProperties.Provider();
        openAi.setType("openai");
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setProviders(new LinkedHashMap<>(Map.of("openai", openAi)));
        properties.setModels(new LinkedHashMap<>(Map.of(
                "unsupported", new ScoreAiProperties.Model())));

        AiModelProfileCatalog.install(properties);

        assertThat(properties.getModels()).containsOnlyKeys(
                "gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna");
    }

    @Test
    void serializesProfilesAsTheFrontendContract() {
        var json = new ObjectMapper().valueToTree(
                AiModelProfileView.from(new Gpt56SolProfile()));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "providerType", "modelKey", "providerModelName", "displayName", "description",
                "maxTokens", "contextWindow", "maxOutputTokens", "maxContextWindow",
                "outputReserveTokens", "autoCompactThresholdTokens", "emergencyHeadroomTokens",
                "toolOutputTokenLimit", "providerCompactionEnabled", "temperature",
                "thinkingBudgetTokens", "minThinkingBudgetTokens", "maxThinkingBudgetTokens",
                "adaptiveThinking", "outputEffort", "cacheStrategy", "reasoningModelSupported",
                "outputEffortSupported", "verbositySupported", "temperatureSupported",
                "thinkingModes", "defaultThinking", "reasoningEfforts",
                "configurationConstraints", "capabilityConstraints");
        assertThat(fieldNames(json.get("configurationConstraints"))).containsExactlyInAnyOrder(
                "contextWindow", "maxOutputTokens", "outputReserveTokens",
                "autoCompactThresholdTokens", "emergencyHeadroomTokens",
                "toolOutputTokenLimit", "thinkingBudgetTokens", "temperature");
        assertThat(fieldNames(json.get("capabilityConstraints"))).containsExactlyInAnyOrder(
                "reasoningOptions", "outputEffort", "verbosity", "temperature",
                "adaptiveThinking", "providerCompaction");
        assertThat(json.get("providerType").asText()).isEqualTo("openai");
        assertThat(json.get("modelKey").asText()).isEqualTo("gpt-5_6-sol");
        assertThat(json.get("contextWindow").asLong()).isEqualTo(1_050_000L);
        assertThat(json.get("maxContextWindow").asLong()).isEqualTo(1_050_000L);
        assertThat(json.get("maxOutputTokens").asInt()).isEqualTo(128_000);
        assertThat(json.get("configurationConstraints").get("contextWindow")
                .get("maximum").asLong()).isEqualTo(1_050_000L);
        assertThat(json.get("capabilityConstraints").get("reasoningOptions")
                .get("supported").asBoolean()).isTrue();
        assertThat(json.get("reasoningEfforts").get(2).get("defaultEffort").asBoolean())
                .isTrue();
    }

    @Test
    void serializesUnsupportedHaikuCapabilitiesWithStableSentinelValues() {
        var json = new ObjectMapper().valueToTree(
                AiModelProfileView.from(new ClaudeHaiku45Profile()));

        assertThat(json.get("reasoningModelSupported").asBoolean()).isFalse();
        assertThat(json.get("outputEffortSupported").asBoolean()).isFalse();
        assertThat(json.get("verbositySupported").asBoolean()).isFalse();
        assertThat(json.get("temperatureSupported").asBoolean()).isFalse();
        assertThat(json.get("providerCompactionEnabled").asBoolean()).isFalse();
        assertThat(json.get("adaptiveThinking").asBoolean()).isFalse();
        assertThat(json.get("temperature").isNull()).isTrue();
        assertThat(json.get("outputEffort").isNull()).isTrue();
        assertThat(json.get("reasoningEfforts").isEmpty()).isTrue();
        assertThat(json.get("thinkingModes")).extracting(node -> node.asText())
                .containsExactly("enabled", "disabled");
        assertThat(json.get("defaultThinking").asText()).isEqualTo("disabled");
        assertThat(json.get("cacheStrategy").asText()).isEqualTo("conversation-history");
        assertThat(json.get("thinkingBudgetTokens").asInt()).isEqualTo(4_096);
        json.get("capabilityConstraints").forEach(capability -> {
            assertThat(capability.get("supported").asBoolean()).isFalse();
            assertThat(capability.get("defaultEnabled").asBoolean()).isFalse();
        });
    }

    @Test
    void rejectsUnsupportedModelKeys() {
        assertThat(AiModelProfileCatalog.find("openai", "unsupported-model")).isEmpty();
    }

    @Test
    void resolvesEditableSettingsWithinProfileLimitsForPersistence() {
        var profile = new ClaudeHaiku45Profile();
        var update = new AiModelCatalogUpdate(null, AiProviderId.from(1L), profile.getModelKey(),
                true, false, 0, 32_000, 100_000L, 32_000L, 60_000L,
                4_096L, 16_000L, false, null, 2_048, false,
                null, "conversation-history", null, false, null, false,
                java.util.List.of("disabled"), "disabled", java.util.List.of());

        AiModelProfileSettingsValidator.validate(profile, update);
        var settings = AiModelProfileSettingsResolver.resolve(profile, update);

        assertThat(settings.contextWindow()).isEqualTo(100_000L);
        assertThat(settings.maxTokens()).isEqualTo(32_000);
        assertThat(settings.outputReserveTokens()).isEqualTo(32_000L);
        assertThat(settings.autoCompactThresholdTokens()).isEqualTo(60_000L);
        assertThat(settings.thinkingBudgetTokens()).isEqualTo(2_048);
        assertThat(settings.adaptiveThinking()).isFalse();
    }

    @Test
    void normalizesOptionalNullSettingsToProfileDefaultsBeforePersistence() {
        var profile = new ClaudeHaiku45Profile();
        var update = new AiModelCatalogUpdate(null, AiProviderId.from(1L), profile.getModelKey(),
                true, false, 0, null, 200_000L, null, null,
                8_192L, 32_000L, false, null, null, false,
                null, "conversation-history", null, false, null, false,
                java.util.List.of("enabled", "disabled"), "enabled", java.util.List.of());

        AiModelProfileSettingsValidator.validate(profile, update);
        var settings = AiModelProfileSettingsResolver.resolve(profile, update);

        assertThat(settings.maxTokens()).isEqualTo(64_000);
        assertThat(settings.outputReserveTokens()).isEqualTo(64_000L);
        assertThat(settings.autoCompactThresholdTokens()).isEqualTo(120_000L);
        assertThat(settings.thinkingBudgetTokens()).isEqualTo(4_096);
    }

    private static Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertCapabilityCoherent(
            org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.CapabilityConstraint capability) {
        if (capability.defaultEnabled()) assertThat(capability.supported()).isTrue();
    }

    private static ModelTokenConstraints tokensMissing(String missing) {
        return new ModelTokenConstraints(
                tokenConstraint("contextWindow", missing),
                tokenConstraint("maxOutputTokens", missing),
                tokenConstraint("outputReserveTokens", missing),
                tokenConstraint("autoCompactThresholdTokens", missing),
                tokenConstraint("emergencyHeadroomTokens", missing),
                tokenConstraint("toolOutputTokenLimit", missing));
    }

    private static NumericConstraint tokenConstraint(String name, String missing) {
        return name.equals(missing)
                ? new NumericConstraint(null, null, null, true)
                : new NumericConstraint(10L, 1L, 100L, true);
    }

    private static final class AmbiguousEffortProfile
            implements ReasoningEffortModelProfile, OutputEffortModelProfile {
        @Override public String getProviderType() { return "test"; }
        @Override public String getModelKey() { return "ambiguous"; }
        @Override public String getProviderModelName() { return "ambiguous"; }
        @Override public String getDisplayName() { return "Ambiguous"; }
        @Override public String getDescription() { return "Invalid combined effort profile."; }
        @Override public ModelTokenConstraints getTokenConstraints() {
            return ModelTokenConstraints.standard(100_000L, 20_000L, 20_000L, 60_000L);
        }
        @Override public CapabilityConstraint getOutputEffortCapability() {
            return new CapabilityConstraint(true, true);
        }
        @Override public String getDefaultOutputEffort() { return "high"; }
        @Override public java.util.List<ReasoningEffort> getOutputEfforts() {
            return java.util.List.of(effort("high"));
        }
        @Override public java.util.List<ReasoningEffort> getReasoningEfforts() {
            return java.util.List.of(effort("medium"));
        }
        private ReasoningEffort effort(String name) {
            return new ReasoningEffort(name, name, name, true, 0);
        }
    }
}
