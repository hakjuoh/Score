package org.oagi.score.gateway.http.configuration.ai;

import com.anthropic.models.messages.OutputConfig;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiUiRouteManifest;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates provider-specific Spring AI options for one assistant model call. */
@Component
public final class ScoreAiChatOptionsFactory {

    private final ScoreAiModelRegistry models;
    private final AnthropicChatProperties anthropicProperties;
    private final OpenAiChatProperties openAiProperties;

    public ScoreAiChatOptionsFactory(ScoreAiModelRegistry models,
                                     AnthropicChatProperties anthropicProperties,
                                     OpenAiChatProperties openAiProperties) {
        this.models = models;
        this.anthropicProperties = anthropicProperties;
        this.openAiProperties = openAiProperties;
    }

    public ChatOptions create(String modelName, String reasoningEffort,
                              AiUiRouteManifest routeManifest) {
        ScoreAiModelRegistry.ModelConfiguration model = models.modelConfiguration(modelName);
        return switch (model.providerType()) {
            case "anthropic" -> anthropicOptions(model, reasoningEffort);
            case "openai", "azure-openai" -> openAiOptions(model, reasoningEffort, routeManifest);
            default -> throw new IllegalArgumentException(
                    "Spring AI does not support provider: " + model.providerType());
        };
    }

    private AnthropicChatOptions anthropicOptions(
            ScoreAiModelRegistry.ModelConfiguration model, String reasoningEffort) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model(model.model());
        boolean thinkingModel = model.adaptiveThinking() || model.thinkingBudgetTokens() != null;
        anthropicProperties.apply(builder, thinkingModel);
        if (model.maxTokens() != null) builder.maxTokens(model.maxTokens());
        if (model.temperature() != null && model.supportsTemperature()) {
            builder.temperature(model.temperature());
        }
        String thinking = model.resolvedDefaultThinking();
        String normalizedReasoningEffort = StringUtils.hasText(reasoningEffort)
                ? reasoningEffort.strip().toLowerCase() : "";
        boolean thinkingDisabled = "disabled".equals(normalizedReasoningEffort)
                || "disabled".equals(thinking);
        if ("disabled".equals(normalizedReasoningEffort)) {
            builder.thinkingDisabled();
        } else if ("adaptive".equals(thinking)) {
            builder.thinkingAdaptive();
        } else if ("enabled".equals(thinking) && model.thinkingBudgetTokens() != null) {
            builder.thinkingEnabled(model.thinkingBudgetTokens());
        } else if ("disabled".equals(thinking)) {
            builder.thinkingDisabled();
        }
        if (!thinkingDisabled && model.supportsOutputEffort()
                && StringUtils.hasText(normalizedReasoningEffort)
                && !"default".equals(normalizedReasoningEffort)) {
            builder.effort(OutputConfig.Effort.of(normalizedReasoningEffort));
        }
        if ("conversation-history".equalsIgnoreCase(model.cacheStrategy())) {
            builder.cacheOptions(AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .multiBlockSystemCaching(true)
                    .cacheToolResults(true)
                    .build());
        }
        return builder.build();
    }

    private OpenAiChatOptions openAiOptions(
            ScoreAiModelRegistry.ModelConfiguration model, String reasoningEffort,
            AiUiRouteManifest routeManifest) {
        boolean reasoningModel = model.openAiReasoningModel();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model.model());
        if ("azure-openai".equals(model.providerType())) {
            builder.deploymentName(model.model()).azure(true);
        }
        openAiProperties.apply(builder, reasoningModel);
        if (model.maxTokens() != null) {
            if (reasoningModel) builder.maxCompletionTokens(model.maxTokens());
            else builder.maxTokens(model.maxTokens());
        }
        if (model.temperature() != null && model.supportsTemperature()) {
            builder.temperature(model.temperature());
        }
        if (reasoningModel) {
            // OpenAI's reasoning models require an explicit effort when the setting is absent.
            String effectiveReasoningEffort = !StringUtils.hasText(reasoningEffort)
                    || "disabled".equalsIgnoreCase(reasoningEffort) ? "none" : reasoningEffort;
            if (StringUtils.hasText(effectiveReasoningEffort)
                    && !"default".equalsIgnoreCase(effectiveReasoningEffort)) {
                builder.reasoningEffort(effectiveReasoningEffort.strip().toLowerCase());
            }
        }
        if (routeManifest != null) builder.promptCacheKey(routeManifest.promptCacheKey());
        builder.streamUsage(true);
        return builder.build();
    }
}
