package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic.ClaudeHaiku55Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic.ClaudeOpus55Profile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai.Gpt61SolProfile;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelOptionsTest {

    @Test
    void validatesTypedEditableOptionsAndOmitsBlankValues() {
        var values = AiModelOptions.validated(new ClaudeHaiku55Profile(), Map.of(
                "multiBlockSystemCaching", true,
                "messageTypeTtl", Map.of("SYSTEM", "FIVE_MINUTES"),
                "cacheStrategy", ""));

        assertThat(values).containsEntry("multiBlockSystemCaching", true)
                .containsKey("messageTypeTtl")
                .doesNotContainKey("cacheStrategy");
    }

    @Test
    void rejectsProviderOwnedAndMismatchedOptionValues() {
        var profile = new ClaudeHaiku55Profile();

        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of("apiKey", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported model option");
        assertThatThrownBy(() -> AiModelOptions.validated(new Gpt61SolProfile(), Map.of("topP", "high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match decimal");
        assertThatThrownBy(() -> AiModelOptions.validated(
                profile, Map.of("messageTypeTtl", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match json");
    }

    @Test
    void rejectsUnsafeJsonOverflowRangesAndInvalidDependencies() {
        var profile = new ClaudeHaiku55Profile();

        var openAi = new Gpt61SolProfile();
        assertThatThrownBy(() -> AiModelOptions.validated(openAi, Map.of("topLogprobs", Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiModelOptions.validated(openAi, Map.of("topP", 1.1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiModelOptions.validated(profile,
                Map.of("messageTypeTtl", Map.of("SYSTEM", new Object()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersApplicationManagedAndStaleOptionsFromTheEditableDocument() {
        var profile = new ClaudeHaiku55Profile();

        assertThat(AiModelOptions.editableOptions(profile)).extracting("key")
                .doesNotContain("model", "apiKey", "toolCallbacks", "toolContext",
                        "contentLengthFunction", "toolChoice", "toolChoiceName",
                        "disableParallelToolUse", "webSearchTool", "maxUses",
                        "allowedDomains", "blockedDomains", "userLocation");
        assertThat(AiModelOptions.editable(profile, Map.of(
                "multiBlockSystemCaching", false, "removedOption", true, "adaptiveThinking", true)))
                .containsExactlyEntriesOf(Map.of("multiBlockSystemCaching", false));
    }

    @Test
    void revalidatesPersistedOptionsAgainstTheCurrentProfileBeforeRuntimeUse() {
        Map<String, Object> runtime = AiModelOptions.runtimeOptions(
                new ClaudeOpus55Profile(), Map.of(
                        "topP", 0.5, "adaptiveThinking", true,
                        "thinkingModes", List.of("adaptive"),
                        "defaultThinking", "adaptive",
                        "outputEffort", "medium",
                        "cacheStrategy", "CONVERSATION_HISTORY"));

        assertThat(runtime).containsEntry("adaptiveThinking", true)
                .containsEntry("cacheStrategy", "CONVERSATION_HISTORY")
                .containsEntry("outputEffort", "medium")
                .doesNotContainKey("topP");
    }

    @Test
    void rejectsStaleCapabilityFlagsAndInconsistentThinkingState() {
        assertThatThrownBy(() -> AiModelOptions.runtimeOptions(
                new Gpt61SolProfile(), Map.of("adaptiveThinking", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adaptiveThinking");
        assertThatThrownBy(() -> AiModelOptions.runtimeOptions(
                new ClaudeHaiku55Profile(), Map.of(
                        "adaptiveThinking", true,
                        "thinkingModes", List.of("disabled"),
                        "defaultThinking", "disabled")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adaptiveThinking");
    }

    @Test
    void rejectsToolOptionsAndMalformedCitationDocuments() {
        var profile = new ClaudeHaiku55Profile();
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
    }

    @Test
    void rejectsToolControlsNestedInEditableJsonOptions() {
        var profile = new ClaudeHaiku55Profile();

        assertThatThrownBy(() -> AiModelOptions.validated(profile, Map.of(
                "cacheOptions", Map.of("cacheToolResults", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheOptions");
        assertThatThrownBy(() -> AiModelOptions.validated(new Gpt61SolProfile(), Map.of(
                "extraBody", Map.of("tool_choice", "auto"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extraBody");
    }

    @Test
    void restoresProfileRuntimeDefaultsForLegacyRowsWithoutModelOptions() {
        var runtime = AiModelOptions.runtimeOptions(new ClaudeOpus55Profile(), Map.of());

        assertThat(runtime).containsEntry("adaptiveThinking", true)
                .containsEntry("outputEffort", "medium")
                .containsEntry("cacheStrategy", "CONVERSATION_HISTORY")
                .containsEntry("thinkingModes", List.of("adaptive"))
                .containsEntry("defaultThinking", "adaptive");
    }
}
