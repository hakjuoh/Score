package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.*;
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
                        ClaudeHaiku45Profile.class,
                        ClaudeSonnet45Profile.class, ClaudeSonnet46Profile.class,
                        ClaudeSonnet5Profile.class,
                        ClaudeOpus45Profile.class, ClaudeOpus46Profile.class,
                        ClaudeOpus47Profile.class, ClaudeOpus48Profile.class,
                        ClaudeOpus5Profile.class, ClaudeFable5Profile.class,
                        ClaudeMythos5Profile.class);
        assertThat(AiModelProfileCatalog.modelsFor("openai"))
                .hasExactlyElementsOfTypes(
                        Gpt56SolProfile.class, Gpt56TerraProfile.class, Gpt56LunaProfile.class,
                        Gpt55Profile.class, Gpt55ProProfile.class,
                        Gpt54Profile.class, Gpt54ProProfile.class,
                        Gpt54MiniProfile.class, Gpt54NanoProfile.class);
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
                .containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(sol.getReasoningEfforts()).filteredOn(effort -> effort.defaultEffort())
                .extracting(effort -> effort.name()).containsExactly("medium");
        assertThat(view.capabilityConstraints().providerCompaction().supported()).isTrue();
        assertThat(view.capabilityConstraints().providerCompaction().defaultEnabled()).isFalse();
    }

    @Test
    void everyProfilePublishesTypedDocumentedOptions() {
        Stream.of("anthropic", "openai")
                .flatMap(provider -> AiModelProfileCatalog.modelsFor(provider).stream())
                .forEach(profile -> {
                    assertThat(profile.getOptions()).isNotEmpty();
                    assertThat(profile.getOptions()).extracting(AiModelOption::key)
                            .doesNotHaveDuplicates();
                    assertThat(profile.getOptions()).allSatisfy(option -> {
                        assertThat(option.key()).isNotBlank();
                        assertThat(option.type()).isIn(
                                "boolean", "integer", "decimal", "string", "json", "enum");
                        assertThat(option.description()).isNotBlank();
                        if (option.type().equals("enum")) {
                            assertThat(option.allowedValues()).isNotEmpty();
                            if (option.value() != null) {
                                assertThat(option.value()).isIn(option.allowedValues());
                            }
                        } else {
                            assertThat(option.allowedValues()).isEmpty();
                        }
                        if (option.type().equals("json") && option.value() != null) {
                            assertThat(option.value()).isInstanceOfAny(
                                    java.util.Map.class, java.util.List.class, String.class,
                                    Number.class, Boolean.class);
                        }
                    });
                });
    }

    @Test
    void anthropicOptionsFollowDocumentedModelVersionBoundaries() {
        var haiku45 = optionKeys(new ClaudeHaiku45Profile());
        var sonnet45 = optionKeys(new ClaudeSonnet45Profile());
        var sonnet46 = optionKeys(new ClaudeSonnet46Profile());
        var opus46 = optionKeys(new ClaudeOpus46Profile());
        var opus47 = optionKeys(new ClaudeOpus47Profile());

        assertThat(haiku45).contains("topP", "topK", "cacheStrategy")
                .doesNotContain("citationsEnabled", "outputConfig", "skillContainer");
        assertThat(sonnet45).contains("topP", "topK", "citationsEnabled", "skillContainer")
                .doesNotContain("outputConfig", "outputSchema", "outputEffort");
        assertThat(sonnet46).contains("topP", "topK", "outputConfig", "outputSchema",
                "outputEffort", "citationsEnabled");
        assertThat(opus46).contains("topP", "topK", "outputConfig");
        assertThat(option(new ClaudeSonnet46Profile(), "thinking").description())
                .containsIgnoringCase("enabled fixed thinking is deprecated")
                .containsIgnoringCase("use adaptive instead");
        assertThat(option(new ClaudeOpus46Profile(), "thinking").description())
                .containsIgnoringCase("enabled fixed thinking is deprecated")
                .containsIgnoringCase("use adaptive instead");
        assertThat(option(new ClaudeSonnet45Profile(), "thinking").description())
                .doesNotContainIgnoringCase("deprecated");
        assertThat(opus47).contains("outputConfig", "citationsEnabled")
                .doesNotContain("topP", "topK", "thinkingBudgetTokens");
    }

    @Test
    void modelSpecificationsMatchThePublishedProviderMatrices() {
        assertProfile(new ClaudeSonnet45Profile(), 200_000L, 64_000L,
                java.util.List.of("enabled", "disabled"));
        assertProfile(new ClaudeOpus45Profile(), 200_000L, 64_000L,
                java.util.List.of("enabled", "disabled"));
        assertProfile(new ClaudeSonnet46Profile(), 1_000_000L, 128_000L,
                java.util.List.of("enabled", "adaptive", "disabled"));
        assertProfile(new ClaudeOpus46Profile(), 1_000_000L, 128_000L,
                java.util.List.of("enabled", "adaptive", "disabled"));
        assertProfile(new ClaudeOpus47Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive", "disabled"));
        assertProfile(new ClaudeOpus48Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive", "disabled"));
        assertProfile(new ClaudeFable5Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive"));
        assertProfile(new ClaudeMythos5Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive"));

        assertOpenAiProfile(new Gpt54Profile(), 1_050_000L,
                java.util.List.of("low", "medium", "high", "xhigh"),
                "medium", true, true);
        assertOpenAiProfile(new Gpt54ProProfile(), 1_050_000L,
                java.util.List.of("medium", "high", "xhigh"),
                "medium", false, true);
        assertOpenAiProfile(new Gpt54MiniProfile(), 400_000L,
                java.util.List.of("low", "medium", "high", "xhigh"),
                "medium", true, true);
        assertOpenAiProfile(new Gpt54NanoProfile(), 400_000L,
                java.util.List.of("low", "medium", "high", "xhigh"),
                "medium", true, true);
        assertOpenAiProfile(new Gpt55Profile(), 1_050_000L,
                java.util.List.of("low", "medium", "high", "xhigh"),
                "medium", true, true);
        assertOpenAiProfile(new Gpt55ProProfile(), 1_050_000L,
                java.util.List.of("medium", "high", "xhigh"),
                "high", true, false);
        assertOpenAiProfile(new Gpt56SolProfile(), 1_050_000L,
                java.util.List.of("low", "medium", "high", "xhigh", "max"),
                "medium", true, true);
        assertThat(new Gpt54Profile().isChatCompletionsCompatible()).isTrue();
        assertThat(new Gpt54ProProfile().isChatCompletionsCompatible()).isFalse();
        assertThat(new Gpt55ProProfile().isChatCompletionsCompatible()).isFalse();
    }

    @Test
    void gpt5ProfilesUseReasoningOptionsWithoutUnsupportedTemperatureOrMaxTokens() {
        AiModelProfileCatalog.modelsFor("openai").forEach(profile -> {
            assertThat(optionKeys(profile)).contains(
                    "maxCompletionTokens", "reasoningEffort", "verbosity",
                    "promptCacheKey", "serviceTier", "safetyIdentifier")
                    .doesNotContain("maxTokens", "temperature", "frequencyPenalty",
                            "presencePenalty", "logitBias", "logprobs", "n", "seed", "stop",
                            "outputModalities", "streamUsage", "toolChoice", "tools",
                            "toolCallbacks", "toolContext", "parallelToolCalls");
        });
        AiModelProfileCatalog.modelsFor("anthropic").forEach(profile -> {
            assertThat(option(profile, "multiBlockSystemCaching").value()).isEqualTo(true);
            assertThat(optionKeys(profile)).doesNotContain("toolChoice", "toolChoiceName",
                    "toolCallbacks", "toolContext", "disableParallelToolUse",
                    "cacheToolResults", "webSearchTool", "maxUses", "allowedDomains",
                    "blockedDomains", "userLocation");
        });
    }

    @Test
    void baseProfileContainsOnlyCommonModelElements() {
        assertThat(AiModelProfile.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("getProviderType", "getModelKey", "getProviderModelName",
                        "getDisplayName", "getDescription", "getTokenConstraints", "getOptions",
                        "isChatCompletionsCompatible");
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
                .filter(profile -> !profile.getModelKey().endsWith("-4_5")
                        && !profile.getModelKey().endsWith("-4_6"))
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
                .filter(profile -> profile.getModelKey().endsWith("-4_6"))
                .forEach(profile -> assertThat(profile)
                        .isInstanceOf(FixedThinkingModelProfile.class)
                        .isInstanceOf(AdaptiveThinkingModelProfile.class)
                        .isInstanceOf(OutputEffortModelProfile.class)
                        .isInstanceOf(CacheModelProfile.class));
        AiModelProfileCatalog.modelsFor("anthropic").stream()
                .filter(profile -> profile.getModelKey().endsWith("-4_5")
                        && !(profile instanceof ClaudeHaiku45Profile))
                .forEach(profile -> assertThat(profile)
                        .isInstanceOf(FixedThinkingModelProfile.class)
                        .isInstanceOf(CacheModelProfile.class)
                        .isNotInstanceOf(AdaptiveThinkingModelProfile.class)
                        .isNotInstanceOf(OutputEffortModelProfile.class));
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
        openAi.setType("openai");
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setProviders(new LinkedHashMap<>(Map.of(
                "azure-foundry", anthropic, "azure-openai", openAi)));

        AiModelProfileCatalog.install(properties);

        assertThat(properties.getModels()).containsOnlyKeys(
                "claude-haiku-4_5", "claude-sonnet-4_5", "claude-sonnet-4_6",
                "claude-sonnet-5", "claude-opus-4_5", "claude-opus-4_6",
                "claude-opus-4_7", "claude-opus-4_8", "claude-opus-5",
                "claude-fable-5", "claude-mythos-5",
                "gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna",
                "gpt-5_5", "gpt-5_5-pro", "gpt-5_4", "gpt-5_4-pro",
                "gpt-5_4-mini", "gpt-5_4-nano");
        assertThat(properties.getModels().get("claude-haiku-4_5").getReasoningEfforts())
                .isEmpty();
        assertThat(properties.getModels().get("gpt-5_6-sol").getReasoningEfforts())
                .extracting(ScoreAiProperties.ReasoningEffort::getName)
                .containsExactly("low", "medium", "high", "xhigh", "max");
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
                "gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna",
                "gpt-5_5", "gpt-5_5-pro", "gpt-5_4", "gpt-5_4-pro",
                "gpt-5_4-mini", "gpt-5_4-nano");
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
                "thinkingModes", "defaultThinking", "reasoningEfforts", "options",
                "chatCompletionsCompatible", "configurationConstraints",
                "capabilityConstraints");
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
        assertThat(json.get("reasoningEfforts").get(1).get("defaultEffort").asBoolean())
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
        var update = new AiModelCatalogUpdate(AiProviderId.from(1L), profile.getModelKey(),
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
        var update = new AiModelCatalogUpdate(AiProviderId.from(1L), profile.getModelKey(),
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

    private static void assertProfile(AiModelProfile profile, long contextWindow,
                                      long maxOutputTokens,
                                      java.util.List<String> thinkingModes) {
        var view = AiModelProfileView.from(profile);
        assertThat(view.contextWindow()).isEqualTo(contextWindow);
        assertThat(view.maxTokens()).isEqualTo(Math.toIntExact(maxOutputTokens));
        assertThat(view.thinkingModes()).containsExactlyElementsOf(thinkingModes);
    }

    private static void assertOpenAiProfile(
            AiModelProfile profile, long contextWindow,
            java.util.List<String> reasoningEfforts, String defaultEffort,
            boolean structuredOutputs, boolean streaming) {
        var view = AiModelProfileView.from(profile);
        assertThat(view.contextWindow()).isEqualTo(contextWindow);
        assertThat(view.maxTokens()).isEqualTo(128_000);
        assertThat(view.reasoningEfforts()).extracting(ReasoningEffort::name)
                .containsExactlyElementsOf(reasoningEfforts);
        assertThat(view.reasoningEfforts()).filteredOn(ReasoningEffort::defaultEffort)
                .extracting(ReasoningEffort::name).containsExactly(defaultEffort);
        if (structuredOutputs) {
            assertThat(optionKeys(profile)).contains("responseFormatType", "responseFormatName",
                    "responseFormatSchema", "responseFormatStrict");
        } else {
            assertThat(optionKeys(profile))
                    .doesNotContain("responseFormatType", "responseFormatName",
                            "responseFormatSchema", "responseFormatStrict");
        }
        if (streaming) {
            assertThat(optionKeys(profile)).contains("streamOptions", "includeObfuscation",
                    "streamAdditionalProperties");
        } else {
            assertThat(optionKeys(profile)).doesNotContain("streamOptions", "includeObfuscation",
                    "streamAdditionalProperties");
        }
    }

    private static Set<String> optionKeys(AiModelProfile profile) {
        return profile.getOptions().stream().map(AiModelOption::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static AiModelOption option(AiModelProfile profile, String key) {
        return profile.getOptions().stream().filter(value -> value.key().equals(key))
                .findFirst().orElseThrow();
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
        @Override public java.util.List<AiModelOption> getOptions() { return java.util.List.of(); }
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
