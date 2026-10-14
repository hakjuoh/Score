package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.*;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic.*;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai.*;
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
                        ClaudeHaiku55Profile.class, ClaudeSonnet55Profile.class,
                        ClaudeOpus55Profile.class);
        assertThat(AiModelProfileCatalog.modelsFor("openai"))
                .hasExactlyElementsOfTypes(
                        Gpt6LunaProfile.class, Gpt61SolProfile.class, Gpt6AstraProfile.class);
    }

    @Test
    void haikuAcceptsAdaptiveAndDisabledThinkingWithTheFullEffortLadder() {
        var haiku = new ClaudeHaiku55Profile();
        var view = AiModelProfileView.from(haiku);

        assertThat(view.adaptiveThinking()).isTrue();
        assertThat(haiku.getThinkingModes()).containsExactly("adaptive", "disabled");
        assertThat(haiku.getDefaultThinking()).isEqualTo("adaptive");
        assertThat(view.reasoningEfforts()).extracting(ReasoningEffort::name)
                .containsExactly("low", "medium", "high", "xhigh", "max");
    }

    @Test
    void currentOpenAiProfilesUsePublishedGpt6LimitsAndEfforts() {
        var sol = new Gpt61SolProfile();
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
    void anthropicOptionsFollowTheDocumentedClaude5Surface() {
        AiModelProfileCatalog.modelsFor("anthropic").forEach(profile ->
                assertThat(optionKeys(profile)).contains("cacheStrategy", "outputConfig",
                        "outputSchema", "outputEffort", "citationsEnabled", "skillContainer")
                        .doesNotContain("topP", "topK", "thinkingBudgetTokens"));
        assertThat(option(new ClaudeOpus55Profile(), "outputEffort").value()).isEqualTo("MEDIUM");
        assertThat(option(new ClaudeSonnet55Profile(), "outputEffort").value()).isEqualTo("HIGH");
        assertThat(option(new ClaudeHaiku55Profile(), "outputEffort").value()).isEqualTo("MEDIUM");
    }

    @Test
    void anthropicProfilesExposeOnlyTheEffortsAcceptedByEachModel() {
        AiModelProfileCatalog.modelsFor("anthropic").forEach(profile ->
                assertOutputEfforts(profile, "low", "medium", "high", "xhigh", "max"));
        assertDefaultOutputEffort(new ClaudeOpus55Profile(), "medium");
        assertDefaultOutputEffort(new ClaudeSonnet55Profile(), "high");
        assertDefaultOutputEffort(new ClaudeHaiku55Profile(), "medium");
    }

    @Test
    void modelSpecificationsMatchThePublishedProviderMatrices() {
        assertProfile(new ClaudeOpus55Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive"));
        assertProfile(new ClaudeSonnet55Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive"));
        assertProfile(new ClaudeHaiku55Profile(), 1_000_000L, 128_000L,
                java.util.List.of("adaptive", "disabled"));

        var withoutNone = java.util.List.of("low", "medium", "high", "xhigh", "max");
        assertOpenAiProfile(new Gpt6AstraProfile(), 1_050_000L, withoutNone, "medium", true, true);
        assertOpenAiProfile(new Gpt61SolProfile(), 1_050_000L, withoutNone, "medium", true, true);
        assertOpenAiProfile(new Gpt6LunaProfile(), 1_050_000L,
                java.util.List.of("disabled", "low", "medium", "high", "xhigh", "max"),
                "medium", true, true);
    }

    @Test
    void gpt6ProfilesUseReasoningOptionsWithoutUnsupportedTemperatureOrMaxTokens() {
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
        assertThat(new ClaudeHaiku55Profile()).isInstanceOf(AdaptiveThinkingModelProfile.class);
        assertThat(new ClaudeHaiku55Profile()).isInstanceOf(CacheModelProfile.class);
        assertThat(new ClaudeHaiku55Profile())
                .isNotInstanceOf(FixedThinkingModelProfile.class)
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
        AiModelProfileCatalog.modelsFor("anthropic").forEach(profile -> assertThat(profile)
                .isInstanceOf(AdaptiveThinkingModelProfile.class)
                .isInstanceOf(OutputEffortModelProfile.class)
                .isInstanceOf(CacheModelProfile.class)
                .isNotInstanceOf(ReasoningOptionsModelProfile.class)
                .isNotInstanceOf(ReasoningEffortModelProfile.class)
                .isNotInstanceOf(VerbosityModelProfile.class)
                .isNotInstanceOf(ProviderCompactionModelProfile.class)
                .isNotInstanceOf(TemperatureModelProfile.class)
                .isNotInstanceOf(FixedThinkingModelProfile.class));
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
                "claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5",
                "gpt-6-luna", "gpt-6_1-sol", "gpt-6-astra");
        assertThat(properties.getModels().get("claude-haiku-5-5").getReasoningEfforts())
                .extracting(ScoreAiProperties.ReasoningEffort::getName)
                .containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(properties.getModels().get("gpt-6_1-sol").getReasoningEfforts())
                .extracting(ScoreAiProperties.ReasoningEffort::getName)
                .containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(properties.getModels().get("gpt-6-luna").getReasoningEfforts())
                .extracting(ScoreAiProperties.ReasoningEffort::getName)
                .containsExactly("disabled", "low", "medium", "high", "xhigh", "max");
        assertThat(properties.getModels().get("gpt-6_1-sol").getProvider())
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
                "gpt-6-luna", "gpt-6_1-sol", "gpt-6-astra");
    }

    @Test
    void serializesProfilesAsTheFrontendContract() {
        var json = new ObjectMapper().valueToTree(
                AiModelProfileView.from(new Gpt61SolProfile()));

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
        assertThat(json.get("modelKey").asText()).isEqualTo("gpt-6_1-sol");
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
    void serializesHaikuCapabilitiesWithStableSentinelValues() {
        var json = new ObjectMapper().valueToTree(
                AiModelProfileView.from(new ClaudeHaiku55Profile()));

        assertThat(json.get("reasoningModelSupported").asBoolean()).isFalse();
        assertThat(json.get("outputEffortSupported").asBoolean()).isTrue();
        assertThat(json.get("verbositySupported").asBoolean()).isFalse();
        assertThat(json.get("temperatureSupported").asBoolean()).isFalse();
        assertThat(json.get("providerCompactionEnabled").asBoolean()).isFalse();
        assertThat(json.get("adaptiveThinking").asBoolean()).isTrue();
        assertThat(json.get("temperature").isNull()).isTrue();
        assertThat(json.get("outputEffort").asText()).isEqualTo("medium");
        assertThat(json.get("thinkingModes")).extracting(node -> node.asText())
                .containsExactly("adaptive", "disabled");
        assertThat(json.get("defaultThinking").asText()).isEqualTo("adaptive");
        assertThat(json.get("cacheStrategy").asText()).isEqualTo("conversation-history");
        assertThat(json.get("thinkingBudgetTokens").isNull()).isTrue();
    }

    @Test
    void rejectsUnsupportedModelKeys() {
        assertThat(AiModelProfileCatalog.find("openai", "unsupported-model")).isEmpty();
    }

    @Test
    void resolvesEditableSettingsWithinProfileLimitsForPersistence() {
        var profile = new ClaudeHaiku55Profile();
        var update = new AiModelCatalogUpdate(AiProviderId.from(1L), profile.getModelKey(),
                true, false, 0, 32_000, 100_000L, 32_000L, 60_000L,
                4_096L, 16_000L, false, null, null, true,
                "high", "conversation-history", null, true, null, false,
                java.util.List.of("adaptive", "disabled"), "adaptive", java.util.List.of());

        AiModelProfileSettingsValidator.validate(profile, update);
        var settings = AiModelProfileSettingsResolver.resolve(profile, update);

        assertThat(settings.contextWindow()).isEqualTo(100_000L);
        assertThat(settings.maxTokens()).isEqualTo(32_000);
        assertThat(settings.outputReserveTokens()).isEqualTo(32_000L);
        assertThat(settings.autoCompactThresholdTokens()).isEqualTo(60_000L);
        assertThat(settings.adaptiveThinking()).isTrue();
    }

    @Test
    void normalizesOptionalNullSettingsToProfileDefaultsBeforePersistence() {
        var profile = new ClaudeHaiku55Profile();
        var update = new AiModelCatalogUpdate(AiProviderId.from(1L), profile.getModelKey(),
                true, false, 0, null, 1_000_000L, null, null,
                8_192L, 32_000L, false, null, null, true,
                "medium", "conversation-history", null, true, null, false,
                java.util.List.of("adaptive", "disabled"), "adaptive", java.util.List.of());

        AiModelProfileSettingsValidator.validate(profile, update);
        var settings = AiModelProfileSettingsResolver.resolve(profile, update);

        assertThat(settings.maxTokens()).isEqualTo(128_000);
        assertThat(settings.outputReserveTokens()).isEqualTo(128_000L);
        assertThat(settings.autoCompactThresholdTokens()).isEqualTo(800_000L);
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

    private static void assertDefaultOutputEffort(AiModelProfile profile, String expected) {
        assertThat(AiModelProfileView.from(profile).reasoningEfforts())
                .filteredOn(ReasoningEffort::defaultEffort)
                .extracting(ReasoningEffort::name).containsExactly(expected);
        assertThat(((OutputEffortModelProfile) profile).getDefaultOutputEffort())
                .isEqualTo(expected);
    }

    private static void assertOutputEfforts(AiModelProfile profile, String... expected) {
        var view = AiModelProfileView.from(profile);
        assertThat(view.reasoningEfforts()).extracting(ReasoningEffort::name)
                .containsExactly(expected);
        if (expected.length == 0) {
            assertThat(optionKeys(profile)).doesNotContain("outputEffort");
        } else {
            assertThat(option(profile, "outputEffort").allowedValues())
                    .containsExactly(java.util.Arrays.stream(expected)
                            .map(String::toUpperCase).toArray(String[]::new));
        }
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
