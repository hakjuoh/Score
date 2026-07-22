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
            assertThat(openAi.getStreamOptions().includeUsage()).isTrue();
        });
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
}
