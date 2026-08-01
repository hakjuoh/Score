package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiUiRouteManifest;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreAiChatOptionsFactoryTest {

    private final ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
    private final ScoreAiChatOptionsFactory factory = new ScoreAiChatOptionsFactory(
            models, new AnthropicChatProperties(), new OpenAiChatProperties());

    @Test
    void createsAnthropicOptionsFromServerOwnedModelConfiguration() {
        when(models.modelConfiguration("claude-test")).thenReturn(configuration(
                "claude-test", "claude-deployment", "anthropic", 16_000,
                4_096, true, false, List.of("adaptive", "disabled"), "adaptive"));

        ChatOptions options = factory.create("claude-test", "low", null);

        assertThat(options).isInstanceOfSatisfying(AnthropicChatOptions.class, anthropic -> {
            assertThat(anthropic.getModel()).isEqualTo("claude-deployment");
            assertThat(anthropic.getMaxTokens()).isEqualTo(16_000);
            assertThat(anthropic.getThinking()).isNotNull();
            assertThat(anthropic.getOutputConfig().toString()).containsIgnoringCase("low");
        });
    }

    @Test
    void omitsOutputEffortWhenAnthropicReasoningIsDisabled() {
        when(models.modelConfiguration("claude-test")).thenReturn(configuration(
                "claude-test", "claude-deployment", "anthropic", 16_000,
                4_096, true, false, List.of("adaptive", "disabled"), "adaptive"));

        ChatOptions options = factory.create("claude-test", "disabled", null);

        assertThat(options).isInstanceOfSatisfying(AnthropicChatOptions.class, anthropic -> {
            assertThat(anthropic.getThinking()).isNotNull();
            assertThat(anthropic.getOutputConfig()).isNull();
        });
    }

    @Test
    void appliesOpus45OutputEffortWhileFixedThinkingDefaultsToDisabled() {
        var configuration = new ScoreAiModelRegistry.ModelConfiguration(
                "claude-opus-4_5", "claude-opus-4-5", "anthropic",
                64_000, 0.7, 4_096, false, "high", "conversation-history",
                List.of(new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                        "low", "Low", "Lighter reasoning.")),
                false, true, false, true,
                List.of("enabled", "disabled"), "disabled");
        when(models.modelConfiguration("claude-opus-4_5")).thenReturn(configuration);

        ChatOptions options = factory.create("claude-opus-4_5", "low", null);

        assertThat(options).isInstanceOfSatisfying(AnthropicChatOptions.class, anthropic -> {
            assertThat(anthropic.getThinking()).isNotNull();
            assertThat(anthropic.getOutputConfig().toString()).containsIgnoringCase("low");
        });
    }

    @Test
    void createsAzureOpenAiOptionsAndUsesTheRouteManifestAsTheCacheKey() {
        when(models.modelConfiguration("gpt-test")).thenReturn(configuration(
                "gpt-test", "gpt-deployment", "azure-openai", 8_000,
                null, false, true, List.of(), null));
        AiUiRouteManifest manifest = new AiUiRouteManifest(1, List.of(
                new AiUiRouteManifest.Route("business-context", "/contexts",
                        Map.of(), List.of(), List.of(), null)));

        ChatOptions options = factory.create("gpt-test", "high", manifest);

        assertThat(options).isInstanceOfSatisfying(OpenAiChatOptions.class, openAi -> {
            assertThat(openAi.getModel()).isEqualTo("gpt-deployment");
            assertThat(openAi.getDeploymentName()).isEqualTo("gpt-deployment");
            assertThat(openAi.getMaxCompletionTokens()).isEqualTo(8_000);
            assertThat(openAi.getReasoningEffort()).isEqualTo("high");
            assertThat(openAi.getPromptCacheKey()).isEqualTo(manifest.promptCacheKey());
            assertThat(openAi.getStreamOptions()).isNull();
        });
    }

    @Test
    void sendsNoneReasoningEffortWhenOpenAiReasoningEffortIsAbsent() {
        when(models.modelConfiguration("gpt-test")).thenReturn(configuration(
                "gpt-test", "gpt-deployment", "openai", 8_000,
                null, false, true, List.of(), null));

        ChatOptions options = factory.create("gpt-test", null, null);

        assertThat(options).isInstanceOfSatisfying(OpenAiChatOptions.class, openAi ->
                assertThat(openAi.getReasoningEffort()).isEqualTo("none"));

        ChatOptions blankOptions = factory.create("gpt-test", "  ", null);

        assertThat(blankOptions).isInstanceOfSatisfying(OpenAiChatOptions.class, openAi ->
                assertThat(openAi.getReasoningEffort()).isEqualTo("none"));

        ChatOptions disabledOptions = factory.create("gpt-test", "disabled", null);

        assertThat(disabledOptions).isInstanceOfSatisfying(OpenAiChatOptions.class, openAi ->
                assertThat(openAi.getReasoningEffort()).isEqualTo("none"));
    }

    @Test
    void rebuildsOpenAiChatOptionsFromTheCatalogJsonValues() {
        when(models.modelConfiguration("gpt-test")).thenReturn(configuration(
                "gpt-test", "gpt-deployment", "openai", 8_000,
                null, false, true, List.of(), null,
                Map.ofEntries(
                        Map.entry("store", false), Map.entry("verbosity", "high"),
                        Map.entry("topP", 0.8), Map.entry("stop", List.of("END")),
                        Map.entry("metadata", Map.of("team", "search")),
                        Map.entry("safetyIdentifier", "hashed-user"),
                        Map.entry("responseFormatType", "JSON_SCHEMA"),
                        Map.entry("responseFormatName", "answer_schema"),
                        Map.entry("responseFormatSchema", Map.of("type", "object")),
                        Map.entry("responseFormatStrict", true),
                        Map.entry("extraBody", Map.of("trace", true)))));

        ChatOptions options = factory.create("gpt-test", "medium", null);

        assertThat(options).isInstanceOfSatisfying(OpenAiChatOptions.class, openAi -> {
            assertThat(openAi.getStore()).isFalse();
            assertThat(openAi.getVerbosity()).isEqualTo("high");
            assertThat(openAi.getTopP()).isEqualTo(0.8);
            assertThat(openAi.getStop()).containsExactly("END");
            assertThat(openAi.getMetadata()).containsEntry("team", "search");
            assertThat(openAi.getResponseFormat().getType())
                    .isEqualTo(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
            assertThat(openAi.getExtraBody()).containsEntry("trace", true)
                    .containsEntry(OpenAiResponsesRequestMapper.SAFETY_IDENTIFIER_OPTION,
                            "hashed-user")
                    .containsEntry(OpenAiResponsesRequestMapper.RESPONSE_FORMAT_NAME_OPTION,
                            "answer_schema")
                    .containsEntry(OpenAiResponsesRequestMapper.RESPONSE_FORMAT_STRICT_OPTION, true);
        });
    }

    @Test
    void rebuildsCompositeAnthropicOptionsFromTheCatalogJsonValues() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("metadata", Map.of("user_id", "user-7", "trace", "catalog"));
        options.put("toolChoice", "TOOL");
        options.put("toolChoiceName", "lookup");
        options.put("messageTypeTtl", Map.of("SYSTEM", "ONE_HOUR"));
        options.put("messageTypeMinContentLengths", Map.of("SYSTEM", 12));
        options.put("citationsEnabled", true);
        options.put("citationDocuments", List.of(Map.of("type", "PLAIN_TEXT",
                "text", "Source")));
        options.put("skillContainer", List.of(Map.of("name", "xlsx", "version", "latest")));
        options.put("webSearchTool", Map.of("maxUses", 2,
                "allowedDomains", List.of("example.com")));
        when(models.modelConfiguration("claude-test")).thenReturn(configuration(
                "claude-test", "claude-deployment", "anthropic", 16_000,
                4_096, true, false, List.of("adaptive", "disabled"), "adaptive", options));

        ChatOptions created = factory.create("claude-test", "high", null);

        assertThat(created).isInstanceOfSatisfying(AnthropicChatOptions.class, anthropic -> {
            assertThat(anthropic.getMetadata().userId()).contains("user-7");
            assertThat(anthropic.getToolChoice().isTool()).isTrue();
            assertThat(anthropic.getCacheOptions().getMessageTypeTtl())
                    .containsEntry(org.springframework.ai.chat.messages.MessageType.SYSTEM,
                            org.springframework.ai.anthropic.AnthropicCacheTtl.ONE_HOUR);
            assertThat(anthropic.getCitationDocuments()).hasSize(1);
            assertThat(anthropic.getCitationDocuments().getFirst().isCitationsEnabled()).isTrue();
            assertThat(anthropic.getSkillContainer().getSkills()).hasSize(1);
            assertThat(anthropic.getWebSearchTool().getMaxUses()).isEqualTo(2L);
        });
    }

    @Test
    void rebuildsNamedOpenAiToolChoiceAndCompositeStreamOptions() throws Exception {
        Map<String, Object> namedTool = Map.of("type", "function",
                "function", Map.of("name", "lookup"));
        when(models.modelConfiguration("gpt-test")).thenReturn(configuration(
                "gpt-test", "gpt-deployment", "openai", 8_000,
                null, false, true, List.of(), null,
                Map.of("toolChoice", namedTool, "streamOptions",
                        Map.of("includeObfuscation", true))));

        ChatOptions created = factory.create("gpt-test", "medium", null);

        assertThat(created).isInstanceOfSatisfying(OpenAiChatOptions.class, openAi -> {
            assertThat(openAi.getToolChoice()).isInstanceOf(String.class);
            assertThat(openAi.getStreamOptions().includeUsage()).isNull();
            assertThat(openAi.getStreamOptions().includeObfuscation()).isTrue();
        });
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                (String) ((OpenAiChatOptions) created).getToolChoice(), Map.class))
                .isEqualTo(namedTool);
    }

    @Test
    void rejectsProvidersWithoutASpringAiAdapter() {
        when(models.modelConfiguration("unsupported")).thenReturn(configuration(
                "unsupported", "unsupported", "custom", null,
                null, false, false, List.of(), null));

        assertThatThrownBy(() -> factory.create("unsupported", "default", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Spring AI does not support provider: custom");
    }

    private ScoreAiModelRegistry.ModelConfiguration configuration(
            String name, String model, String providerType, Integer maxTokens,
            Integer thinkingBudgetTokens, boolean adaptiveThinking,
            boolean reasoningModel, List<String> thinkingModes, String defaultThinking) {
        return new ScoreAiModelRegistry.ModelConfiguration(
                name, model, providerType, maxTokens, 0.7, thinkingBudgetTokens,
                adaptiveThinking, adaptiveThinking ? "high" : null,
                "anthropic".equals(providerType) ? "conversation-history" : null,
                List.of(new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                        "high", "High", "Greater reasoning depth.")),
                reasoningModel, adaptiveThinking, reasoningModel, false,
                thinkingModes, defaultThinking);
    }

    private ScoreAiModelRegistry.ModelConfiguration configuration(
            String name, String model, String providerType, Integer maxTokens,
            Integer thinkingBudgetTokens, boolean adaptiveThinking,
            boolean reasoningModel, List<String> thinkingModes, String defaultThinking,
            Map<String, Object> modelOptions) {
        return new ScoreAiModelRegistry.ModelConfiguration(
                name, model, providerType, maxTokens, 0.7, thinkingBudgetTokens,
                adaptiveThinking, adaptiveThinking ? "high" : null,
                "anthropic".equals(providerType) ? "conversation-history" : null,
                modelOptions, List.of(new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                        "high", "High", "Greater reasoning depth.")),
                reasoningModel, adaptiveThinking, reasoningModel, false,
                thinkingModes, defaultThinking);
    }
}
