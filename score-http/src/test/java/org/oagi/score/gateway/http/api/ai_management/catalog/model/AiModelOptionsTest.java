package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeHaiku45Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeOpus47Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.Gpt54Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeFable5Profile;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelOptionsTest {

    @Test
    void validatesTypedEditableOptionsAndOmitsBlankValues() {
        var values = AiModelOptions.validated(new ClaudeHaiku45Profile(), Map.of(
                "topP", 0.7,
                "multiBlockSystemCaching", true,
                "messageTypeTtl", Map.of("SYSTEM", "FIVE_MINUTES"),
                "cacheStrategy", ""));

        assertThat(values).containsEntry("topP", 0.7)
                .containsEntry("multiBlockSystemCaching", true)
                .containsKey("messageTypeTtl")
                .doesNotContainKey("cacheStrategy");
    }

    @Test
    void rejectsProviderOwnedAndMismatchedOptionValues() {
        var profile = new ClaudeHaiku45Profile();

        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of("apiKey", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported model option");
        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of("topP", "high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match decimal");
        assertThatThrownBy(() -> AiModelOptions.validated(
                profile, Map.of("messageTypeTtl", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match json");
    }

    @Test
    void rejectsUnsafeJsonOverflowRangesAndInvalidDependencies() {
        var profile = new ClaudeHaiku45Profile();

        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of("topK", Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of("topP", 1.1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiModelOptions.validated(profile,
                Map.of("messageTypeTtl", Map.of("SYSTEM", new Object()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersApplicationManagedAndStaleOptionsFromTheEditableDocument() {
        var profile = new ClaudeHaiku45Profile();

        assertThat(AiModelOptions.editableOptions(profile)).extracting("key")
                .doesNotContain("model", "apiKey", "toolCallbacks", "toolContext",
                        "contentLengthFunction", "toolChoice", "toolChoiceName",
                        "disableParallelToolUse", "webSearchTool", "maxUses",
                        "allowedDomains", "blockedDomains", "userLocation");
        assertThat(AiModelOptions.editable(profile, Map.of(
                "topP", 0.5, "removedOption", true, "adaptiveThinking", true)))
                .containsExactlyEntriesOf(Map.of("topP", 0.5));
    }

    @Test
    void revalidatesPersistedOptionsAgainstTheCurrentProfileBeforeRuntimeUse() {
        Map<String, Object> runtime = AiModelOptions.runtimeOptions(
                new ClaudeOpus47Profile(), Map.of(
                        "topP", 0.5, "adaptiveThinking", true,
                        "thinkingModes", List.of("adaptive", "disabled"),
                        "defaultThinking", "adaptive",
                        "outputEffort", "high",
                        "cacheStrategy", "CONVERSATION_HISTORY"));

        assertThat(runtime).containsEntry("adaptiveThinking", true)
                .containsEntry("cacheStrategy", "CONVERSATION_HISTORY")
                .containsEntry("outputEffort", "high")
                .doesNotContainKey("topP");
    }

    @Test
    void fixedThinkingDefaultsRoundTripWithTheirDormantBudget() {
        var profile = new ClaudeHaiku45Profile();
        var view = AiModelProfileView.from(profile);
        var input = new AiModelCatalogUpdate(1L, AiProviderId.from(1L),
                profile.getModelKey(), true, false, 0, view.maxTokens(),
                view.contextWindow(), view.outputReserveTokens(),
                view.autoCompactThresholdTokens(), view.emergencyHeadroomTokens(),
                view.toolOutputTokenLimit(), view.providerCompactionEnabled(),
                view.temperature(), view.thinkingBudgetTokens(), view.adaptiveThinking(),
                view.outputEffort(), view.cacheStrategy(), view.reasoningModelSupported(),
                view.outputEffortSupported(), view.verbositySupported(),
                view.temperatureSupported(), List.of("disabled"), view.defaultThinking(),
                Map.of("thinking", "disabled"), List.of());
        var stored = AiModelOptions.persisted(profile, input,
                AiModelProfileSettingsResolver.resolve(profile, input));

        assertThat(stored).containsEntry("thinkingModes", List.of("enabled", "disabled"))
                .containsEntry("thinkingBudgetTokens", view.thinkingBudgetTokens());
        assertThat(AiModelOptions.runtimeOptions(profile, stored))
                .containsEntry("defaultThinking", "disabled");
    }

    @Test
    void rejectsStaleCapabilityFlagsAndInconsistentThinkingState() {
        assertThatThrownBy(() -> AiModelOptions.runtimeOptions(
                new ClaudeHaiku45Profile(), Map.of("adaptiveThinking", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adaptiveThinking");
        assertThatThrownBy(() -> AiModelOptions.runtimeOptions(
                new ClaudeOpus47Profile(), Map.of(
                        "adaptiveThinking", true,
                        "thinkingModes", List.of("disabled"),
                        "defaultThinking", "disabled")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adaptiveThinking");
    }

    @Test
    void rejectsToolOptionsAndMalformedCitationDocuments() {
        var profile = new ClaudeHaiku45Profile();
        assertThatThrownBy(() -> AiModelOptions.validated(profile,
                Map.of("toolChoice", "invented")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported model option");
        assertThatThrownBy(() -> AiModelOptions.validated(profile,
                Map.of("citationDocuments", List.of(Map.of(
                        "type", "PDF", "data", "not-base64")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiModelOptions.validated(profile,
                Map.of("pdf", "not-base64")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiModelOptions.runtimeOptions(profile,
                Map.of("temperature", 9.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("temperature");
        assertThatThrownBy(() -> AiModelOptions.runtimeOptions(profile, Map.of(
                "thinkingBudgetTokens", Integer.MAX_VALUE,
                "thinkingModes", List.of("enabled", "disabled"),
                "adaptiveThinking", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thinkingBudgetTokens");
    }

    @Test
    void rejectsToolControlsNestedInEditableJsonOptions() {
        var profile = new ClaudeHaiku45Profile();

        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of(
                "cacheOptions", Map.of("cacheToolResults", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheOptions");
        assertThatThrownBy(() -> AiModelOptions.validated(new Gpt54Profile(), Map.of(
                "extraBody", Map.of("tool_choice", "auto"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extraBody");
    }

    @Test
    void normalizesLegacyFixedThinkingModesBeforeRuntimeValidation() {
        var runtime = AiModelOptions.runtimeOptions(new ClaudeHaiku45Profile(), Map.of(
                "thinkingBudgetTokens", 4096,
                "adaptiveThinking", false,
                "thinkingModes", List.of("disabled"),
                "defaultThinking", "disabled"));

        assertThat(runtime).containsEntry("thinkingModes", List.of("enabled", "disabled"))
                .containsEntry("defaultThinking", "disabled");

        var withoutStoredModes = AiModelOptions.runtimeOptions(new ClaudeHaiku45Profile(), Map.of(
                "thinkingBudgetTokens", 4096,
                "adaptiveThinking", false));
        assertThat(withoutStoredModes)
                .containsEntry("thinkingModes", List.of("enabled", "disabled"));
    }

    @Test
    void restoresProfileRuntimeDefaultsForLegacyRowsWithoutModelOptions() {
        var runtime = AiModelOptions.runtimeOptions(new ClaudeFable5Profile(), Map.of());

        assertThat(runtime).containsEntry("adaptiveThinking", true)
                .containsEntry("outputEffort", "high")
                .containsEntry("cacheStrategy", "CONVERSATION_HISTORY")
                .containsEntry("thinkingModes", List.of("adaptive"))
                .containsEntry("defaultThinking", "adaptive");
    }
}
