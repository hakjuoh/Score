package org.oagi.score.gateway.http.configuration.ai;

import com.anthropic.models.messages.OutputConfig;
import com.openai.azure.AzureOpenAIServiceVersion;
import org.oagi.score.gateway.http.api.ai_management.runtime.AnthropicRuntimeProperties;
import org.oagi.score.gateway.http.api.ai_management.runtime.OpenAiRuntimeProperties;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({ScoreAiProperties.class, AnthropicRuntimeProperties.class,
        OpenAiRuntimeProperties.class})
public class ScoreAiConfiguration {

    @Bean("scoreAiChatModels")
    public Map<String, ChatModel> scoreAiChatModels(ScoreAiProperties properties,
                                                    AnthropicRuntimeProperties anthropicProperties,
                                                    OpenAiRuntimeProperties openAiProperties) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        properties.getModels().forEach((name, model) -> {
            validateContextBudget(name, model);
            ScoreAiProperties.Provider provider = properties.getProviders().get(model.getProvider());
            if (provider == null) {
                throw new IllegalArgumentException("Unknown AI provider '" + model.getProvider() + "' for model '" + name + "'");
            }
            if (!isConfigured(provider)) {
                return;
            }
            models.put(name, chatModel(name, model, provider, properties.getRequestTimeout(),
                    anthropicProperties, openAiProperties));
        });
        return Map.copyOf(models);
    }

    @Bean
    public ScoreAiSystemPrompt scoreAiSystemPrompt(
            ScoreAiProperties properties, ResourceLoader resourceLoader) {
        String location = properties.getAssistant().getSystemPromptResource();
        if (!StringUtils.hasText(location)) {
            throw new IllegalArgumentException("AI assistant system prompt resource must be configured");
        }
        return new ScoreAiSystemPrompt(resourceLoader.getResource(location.strip()));
    }

    private ChatModel chatModel(String configuredName, ScoreAiProperties.Model model,
                                ScoreAiProperties.Provider provider, Duration requestTimeout,
                                AnthropicRuntimeProperties anthropicProperties,
                                OpenAiRuntimeProperties openAiProperties) {
        return switch (providerType(provider)) {
            case "anthropic" -> anthropicModel(configuredName, model, provider, requestTimeout,
                    anthropicProperties);
            case "azure-openai" -> azureOpenAiModel(configuredName, model, provider, requestTimeout,
                    openAiProperties);
            case "openai" -> openAiModel(configuredName, model, provider, requestTimeout, openAiProperties);
            default -> throw new IllegalArgumentException("Unsupported AI provider type '"
                    + provider.getType() + "' for model '" + configuredName + "'");
        };
    }

    @Bean
    public ToolSearchToolCallingAdvisor scoreAiToolSearchAdvisor(ToolIndex toolIndex) {
        return new ScoreToolSearchToolCallingAdvisor(toolIndex);
    }

    @Bean
    public ToolIndex scoreAiToolIndex() {
        return new ScoreToolIndex();
    }

    @Bean(name = "scoreAiChatExecutor", destroyMethod = "close")
    public ExecutorService scoreAiChatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "scoreAiLifecycleScheduler", destroyMethod = "close")
    public ScheduledExecutorService scoreAiLifecycleScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("score-ai-lifecycle-", 0).daemon(true).factory());
    }

    private ChatModel anthropicModel(String configuredName, ScoreAiProperties.Model model,
                                     ScoreAiProperties.Provider provider,
                                     Duration requestTimeout,
                                     AnthropicRuntimeProperties runtimeProperties) {
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .baseUrl(baseUrl(provider))
                .apiKey(provider.getKey())
                .model(StringUtils.hasText(model.getModel()) ? model.getModel() : configuredName);
        runtimeProperties.apply(options, !thinkingModes(model).isEmpty());
        if (model.getMaxTokens() != null) {
            options.maxTokens(model.getMaxTokens());
        }
        if (model.getTemperature() != null && supportsTemperature(model, false)) {
            options.temperature(model.getTemperature());
        }
        String defaultThinking = defaultThinking(model);
        if ("adaptive".equals(defaultThinking)) {
            options.thinkingAdaptive();
        } else if ("enabled".equals(defaultThinking)
                && model.getThinkingBudgetTokens() != null && model.getThinkingBudgetTokens() >= 1024) {
            options.thinkingEnabled(model.getThinkingBudgetTokens());
        } else if ("disabled".equals(defaultThinking)) {
            options.thinkingDisabled();
        }
        if (!"disabled".equals(defaultThinking) && supportsOutputEffort(model)
                && StringUtils.hasText(model.getOutputEffort())) {
            options.effort(OutputConfig.Effort.of(model.getOutputEffort().toLowerCase()));
        }
        if ("conversation-history".equalsIgnoreCase(model.getCacheStrategy())) {
            options.cacheOptions(AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .multiBlockSystemCaching(true)
                    .cacheToolResults(true)
                    .build());
        }
        if (StringUtils.hasText(provider.getAnthropicVersion())) {
            options.customHeaders(Map.of("anthropic-version", provider.getAnthropicVersion()));
        }
        // The application-level provider retry loop owns backoff and narrates every
        // attempt to the user; silent SDK-internal retries would multiply it.
        options.maxRetries(0);
        return AnthropicChatModel.builder()
                .options(options.build())
                .httpClientBuilderCustomizer(builder -> builder.timeout(requestTimeout))
                .build();
    }

    private ChatModel azureOpenAiModel(String configuredName, ScoreAiProperties.Model model,
                                       ScoreAiProperties.Provider provider,
                                       Duration requestTimeout,
                                       OpenAiRuntimeProperties runtimeProperties) {
        OpenAiChatOptions.Builder options = openAiOptions(configuredName, model, provider, runtimeProperties)
                .deploymentName(StringUtils.hasText(model.getModel()) ? model.getModel() : configuredName)
                .azure(true);
        if (StringUtils.hasText(provider.getApiVersion())) {
            options.azureOpenAIServiceVersion(AzureOpenAIServiceVersion.fromString(provider.getApiVersion()));
        }
        return openAiChatModel(options, requestTimeout, providerCompactThreshold(model));
    }

    private ChatModel openAiModel(String configuredName, ScoreAiProperties.Model model,
                                  ScoreAiProperties.Provider provider,
                                  Duration requestTimeout,
                                  OpenAiRuntimeProperties runtimeProperties) {
        return openAiChatModel(openAiOptions(configuredName, model, provider, runtimeProperties), requestTimeout,
                providerCompactThreshold(model));
    }

    private ChatModel openAiChatModel(OpenAiChatOptions.Builder options,
                                      Duration requestTimeout, Long compactThreshold) {
        return OpenAiResponsesChatModel.create(options.build(), requestTimeout, compactThreshold);
    }

    private Long providerCompactThreshold(ScoreAiProperties.Model model) {
        if (model.getContextWindow() == null || !model.getContextBudget().isProviderCompactionEnabled()) {
            return null;
        }
        return model.getContextBudget().getAutoCompactThresholdTokens();
    }

    private void validateContextBudget(String name, ScoreAiProperties.Model model) {
        if (model.getContextWindow() == null) return;
        long window = model.getContextWindow();
        ScoreAiProperties.ContextBudget budget = model.getContextBudget();
        long reserve = budget.getOutputReserveTokens() != null
                ? budget.getOutputReserveTokens()
                : model.getMaxTokens() != null ? model.getMaxTokens() : Math.min(32768L, window / 6L);
        long headroom = budget.getEmergencyHeadroomTokens() != null
                ? budget.getEmergencyHeadroomTokens() : 4096L;
        long toolLimit = budget.getToolOutputTokenLimit() != null
                ? budget.getToolOutputTokenLimit() : 32000L;
        if (window <= 0 || reserve < 0 || headroom < 0 || toolLimit <= 0) {
            throw new IllegalArgumentException("AI context budget values must be positive for model '" + name + "'.");
        }
        if (reserve >= window || headroom >= window - reserve) {
            throw new IllegalArgumentException("AI auto-compact threshold must leave output reserve and emergency "
                    + "headroom for model '" + name + "'.");
        }
        long safeInput = window - reserve - headroom;
        long threshold = budget.getAutoCompactThresholdTokens() != null
                ? budget.getAutoCompactThresholdTokens() : Math.max(1L, safeInput - safeInput / 5L);
        if (threshold <= 0 || threshold > safeInput || toolLimit > safeInput) {
            throw new IllegalArgumentException("AI auto-compact threshold and tool output limit must fit the safe "
                    + "input budget for model '" + name + "'.");
        }
    }

    private OpenAiChatOptions.Builder openAiOptions(String configuredName, ScoreAiProperties.Model model,
                                                    ScoreAiProperties.Provider provider,
                                                    OpenAiRuntimeProperties runtimeProperties) {
        String deploymentName = StringUtils.hasText(model.getModel()) ? model.getModel() : configuredName;
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(trimTrailingSlashes(provider.getBaseUrl()))
                .apiKey(provider.getKey())
                .model(deploymentName);
        boolean reasoningModel = openAiReasoningModel(model, deploymentName);
        runtimeProperties.apply(options, reasoningModel);
        if (model.getMaxTokens() != null) {
            if (reasoningModel) options.maxCompletionTokens(model.getMaxTokens());
            else options.maxTokens(model.getMaxTokens());
        }
        if (model.getTemperature() != null && supportsTemperature(model, reasoningModel)) {
            options.temperature(model.getTemperature());
        }
        if (reasoningModel && StringUtils.hasText(model.getReasoningEffort())
                && !"default".equalsIgnoreCase(model.getReasoningEffort())) {
            options.reasoningEffort(model.getReasoningEffort().strip().toLowerCase());
        }
        // The application-level provider retry loop owns backoff and narrates every
        // attempt to the user; silent SDK-internal retries would multiply it.
        options.maxRetries(0);
        return options;
    }

    private boolean openAiReasoningModel(ScoreAiProperties.Model model, String modelName) {
        Boolean configured = model.getRuntimeCapabilities().getReasoningModel();
        if (configured != null) return configured;
        String normalized = modelName.toLowerCase();
        return normalized.startsWith("gpt-5") || normalized.matches("o[134](?:[-_].*)?")
                || model.getReasoningEfforts().stream().anyMatch(effort -> effort != null
                && StringUtils.hasText(effort.getName()) && !"default".equalsIgnoreCase(effort.getName()));
    }

    private boolean supportsTemperature(ScoreAiProperties.Model model, boolean reasoningModel) {
        Boolean configured = model.getRuntimeCapabilities().getTemperature();
        return configured != null ? configured : !reasoningModel && thinkingModes(model).isEmpty();
    }

    private boolean supportsOutputEffort(ScoreAiProperties.Model model) {
        Boolean configured = model.getRuntimeCapabilities().getOutputEffort();
        return configured != null ? configured
                : model.isAdaptiveThinking() || StringUtils.hasText(model.getOutputEffort());
    }

    private java.util.List<String> thinkingModes(ScoreAiProperties.Model model) {
        java.util.List<String> configured = model.getRuntimeCapabilities().getThinkingModes().stream()
                .filter(StringUtils::hasText).map(value -> value.strip().toLowerCase()).distinct().toList();
        if (!configured.isEmpty()) return configured;
        if (model.isAdaptiveThinking()) return java.util.List.of("adaptive", "disabled");
        if (model.getThinkingBudgetTokens() != null) return java.util.List.of("enabled", "disabled");
        return java.util.List.of();
    }

    private String defaultThinking(ScoreAiProperties.Model model) {
        String configured = model.getRuntimeCapabilities().getDefaultThinking();
        if (StringUtils.hasText(configured) && thinkingModes(model).contains(configured.strip().toLowerCase())) {
            return configured.strip().toLowerCase();
        }
        if (model.isAdaptiveThinking() && thinkingModes(model).contains("adaptive")) return "adaptive";
        if (model.getThinkingBudgetTokens() != null && thinkingModes(model).contains("enabled")) return "enabled";
        return thinkingModes(model).contains("disabled") ? "disabled" : null;
    }

    private String providerType(ScoreAiProperties.Provider provider) {
        return StringUtils.hasText(provider.getType()) ? provider.getType().strip().toLowerCase() : "anthropic";
    }

    private boolean isConfigured(ScoreAiProperties.Provider provider) {
        return StringUtils.hasText(provider.getKey())
                && (StringUtils.hasText(provider.getBaseUrl()) || StringUtils.hasText(provider.getMessagesUrl()));
    }

    private String baseUrl(ScoreAiProperties.Provider provider) {
        if (StringUtils.hasText(provider.getBaseUrl())) {
            String baseUrl = trimTrailingSlashes(provider.getBaseUrl());
            URI uri = URI.create(baseUrl);
            String path = uri.getPath();
            if (uri.getHost() != null && uri.getHost().endsWith(".services.ai.azure.com")
                    && (!StringUtils.hasText(path) || "/".equals(path))) {
                return baseUrl + "/anthropic";
            }
            return baseUrl;
        }
        if (!StringUtils.hasText(provider.getMessagesUrl())) {
            return null;
        }
        URI uri = URI.create(provider.getMessagesUrl());
        String path = uri.getPath();
        if (path == null || !path.endsWith("/v1/messages")) {
            throw new IllegalArgumentException("Anthropic messages URL must end in /v1/messages");
        }
        String basePath = path.substring(0, path.length() - "/v1/messages".length());
        return uri.getScheme() + "://" + uri.getAuthority() + basePath;
    }

    private String trimTrailingSlashes(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("/+$", "") : null;
    }
}
