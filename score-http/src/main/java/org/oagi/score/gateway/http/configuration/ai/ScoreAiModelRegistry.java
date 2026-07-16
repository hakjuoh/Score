package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Resolves the models used by the connectCenter assistant. */
@Component
public class ScoreAiModelRegistry {

    public static final String DEFAULT = "default";
    public static final String CLAUDE = "claude";
    public static final String OPENAI = "openai";

    private final ScoreAiProperties properties;
    private final Map<String, ChatModel> models;

    public ScoreAiModelRegistry(ScoreAiProperties properties,
                                @Qualifier("scoreAiChatModels") Map<String, ChatModel> scoreAiChatModels) {
        this.properties = properties;
        this.models = scoreAiChatModels;
    }

    public boolean isAvailable() {
        return properties.getModels().keySet().stream().anyMatch(this::isAvailable);
    }

    public String modelName() {
        String name = properties.getModelName();
        if (StringUtils.hasText(name) && isAvailable(name)) {
            return name;
        }
        return properties.getModels().keySet().stream()
                .filter(this::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "score.ai.model-name must reference a configured model"));
    }

    public String resolveModelName(String requestedModelName) {
        String name = StringUtils.hasText(requestedModelName) ? requestedModelName.strip() : modelName();
        if (!isAvailable(name)) {
            throw new IllegalArgumentException("The requested assistant model is not available: " + name);
        }
        return name;
    }

    public String resolveReasoningEffort(String modelName, String requestedReasoningEffort) {
        String resolvedModelName = resolveModelName(modelName);
        ScoreAiProperties.Model model = properties.getModels().get(resolvedModelName);
        List<ReasoningEffortDescriptor> efforts = reasoningEfforts(model);
        String requested = StringUtils.hasText(requestedReasoningEffort)
                ? requestedReasoningEffort.strip().toLowerCase() : defaultReasoningEffort(model);
        return efforts.stream()
                .map(ReasoningEffortDescriptor::name)
                .filter(effort -> effort.equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The requested reasoning effort is not available for model '"
                                + resolvedModelName + "': " + requested));
    }

    public String resolveRuntime(String modelName, String requestedRuntime) {
        String resolvedModelName = resolveModelName(modelName);
        List<RuntimeDescriptor> runtimes = runtimes(resolvedModelName);
        String requested = StringUtils.hasText(requestedRuntime)
                ? normalizeRuntime(requestedRuntime) : defaultRuntime(runtimes);
        return runtimes.stream()
                .map(RuntimeDescriptor::name)
                .filter(runtime -> runtime.equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The requested AI runtime is not available for model '"
                                + resolvedModelName + "': " + requested));
    }

    public List<ModelDescriptor> availableModels() {
        String defaultModel = isAvailable() ? modelName() : null;
        return properties.getModels().entrySet().stream()
                .filter(entry -> isAvailable(entry.getKey()))
                .map(entry -> new ModelDescriptor(entry.getKey(), displayName(entry), description(entry),
                        entry.getValue().getProvider(), entry.getKey().equals(defaultModel),
                        defaultReasoningEffort(entry.getValue()), reasoningEfforts(entry.getValue()),
                        defaultRuntime(runtimes(entry.getKey())), runtimes(entry.getKey()),
                        contextBudget(entry.getValue())))
                .toList();
    }

    public ChatClient.Builder clientBuilder(String modelName) {
        ChatModel model = models.get(resolveModelName(modelName));
        if (model == null) {
            throw new IllegalStateException("The assistant model is not configured.");
        }
        return ChatClient.builder(model).defaultAdvisors(new VisibleTextResultAdvisor());
    }

    public RuntimeModel runtimeModel(String modelName) {
        String resolvedModelName = resolveModelName(modelName);
        ScoreAiProperties.Model model = properties.getModels().get(resolvedModelName);
        ScoreAiProperties.Provider provider = properties.getProviders().get(model.getProvider());
        ScoreAiProperties.RuntimeCapabilities capabilities = model.getRuntimeCapabilities();
        String configuredModel = StringUtils.hasText(model.getModel())
                ? model.getModel().strip() : resolvedModelName;
        return new RuntimeModel(resolvedModelName, configuredModel, providerType(provider),
                model.getMaxTokens(), model.getTemperature(), model.getThinkingBudgetTokens(),
                model.isAdaptiveThinking(), model.getOutputEffort(), model.getCacheStrategy(),
                reasoningEfforts(model), capabilities.getReasoningModel(),
                capabilities.getOutputEffort(), capabilities.getVerbosity(),
                capabilities.getTemperature(), capabilities.getThinkingModes().stream()
                        .filter(StringUtils::hasText).map(value -> value.strip().toLowerCase()).distinct().toList(),
                StringUtils.hasText(capabilities.getDefaultThinking())
                        ? capabilities.getDefaultThinking().strip().toLowerCase() : null,
                contextBudget(model));
    }

    private ContextBudgetDescriptor contextBudget(ScoreAiProperties.Model model) {
        ScoreAiProperties.ContextBudget configured = model.getContextBudget();
        Long contextWindow = model.getContextWindow();
        if (contextWindow == null) {
            return new ContextBudgetDescriptor(null, null, null, null, null, false);
        }
        long outputReserve = configured.getOutputReserveTokens() != null
                ? configured.getOutputReserveTokens()
                : model.getMaxTokens() != null ? model.getMaxTokens() : Math.min(32768L, contextWindow / 6L);
        long headroom = configured.getEmergencyHeadroomTokens() != null
                ? configured.getEmergencyHeadroomTokens() : 4096L;
        long safeInputLimit = outputReserve >= contextWindow || headroom >= contextWindow - outputReserve
                ? 1L : contextWindow - outputReserve - headroom;
        long threshold = configured.getAutoCompactThresholdTokens() != null
                ? configured.getAutoCompactThresholdTokens()
                : Math.max(1L, safeInputLimit - safeInputLimit / 5L);
        long toolOutputLimit = configured.getToolOutputTokenLimit() != null
                ? configured.getToolOutputTokenLimit() : 32000L;
        return new ContextBudgetDescriptor(contextWindow, outputReserve, threshold, headroom,
                toolOutputLimit, configured.isProviderCompactionEnabled());
    }

    private boolean isAvailable(String modelName) {
        if (models.isEmpty() || !StringUtils.hasText(modelName)) {
            return false;
        }
        ScoreAiProperties.Model model = properties.getModels().get(modelName);
        if (model == null || !models.containsKey(modelName)) {
            return false;
        }
        ScoreAiProperties.Provider provider = properties.getProviders().get(model.getProvider());
        return provider != null && StringUtils.hasText(provider.getKey())
                && (StringUtils.hasText(provider.getBaseUrl()) || StringUtils.hasText(provider.getMessagesUrl()))
                && !runtimes(modelName).isEmpty();
    }

    private List<RuntimeDescriptor> runtimes(String modelName) {
        ScoreAiProperties.Model model = properties.getModels().get(modelName);
        if (model == null) {
            return List.of();
        }
        return Stream.concat(Stream.of(DEFAULT), model.getRuntimes().stream())
                .filter(StringUtils::hasText)
                .map(this::normalizeRuntime)
                .distinct()
                .filter(runtime -> supportsRuntime(modelName, model, runtime))
                .map(this::runtimeDescriptor)
                .toList();
    }

    private boolean supportsRuntime(String modelName, ScoreAiProperties.Model model, String runtime) {
        ScoreAiProperties.Provider provider = properties.getProviders().get(model.getProvider());
        if (provider == null) {
            return false;
        }
        String type = providerType(provider);
        return switch (runtime) {
            case DEFAULT -> models.containsKey(modelName);
            case CLAUDE -> "anthropic".equals(type);
            case OPENAI -> "azure-openai".equals(type) || "openai".equals(type);
            default -> false;
        };
    }

    private RuntimeDescriptor runtimeDescriptor(String name) {
        return switch (name) {
            case CLAUDE -> new RuntimeDescriptor(name, "Anthropic", "Uses the Anthropic runtime.");
            case OPENAI -> new RuntimeDescriptor(name, "OpenAI", "Uses the OpenAI runtime.");
            default -> new RuntimeDescriptor(name, "Default", "Uses the Default runtime.");
        };
    }

    private String defaultRuntime(List<RuntimeDescriptor> runtimes) {
        return runtimes.stream().map(RuntimeDescriptor::name)
                .filter(DEFAULT::equals)
                .findFirst()
                .orElse(DEFAULT);
    }

    public String providerType(String modelName) {
        ScoreAiProperties.Model model = properties.getModels().get(modelName);
        if (model == null) {
            throw new IllegalArgumentException("Unknown assistant model: " + modelName);
        }
        ScoreAiProperties.Provider provider = properties.getProviders().get(model.getProvider());
        if (provider == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + model.getProvider());
        }
        return providerType(provider);
    }

    private String providerType(ScoreAiProperties.Provider provider) {
        return StringUtils.hasText(provider.getType())
                ? provider.getType().strip().toLowerCase() : "anthropic";
    }

    public String normalizeRuntime(String runtime) {
        if (!StringUtils.hasText(runtime)) {
            return DEFAULT;
        }
        String normalized = runtime.strip().toLowerCase();
        return switch (normalized) {
            case "spring-ai" -> DEFAULT;
            case "claude-sdk" -> CLAUDE;
            case "codex-sdk" -> OPENAI;
            default -> normalized;
        };
    }

    private String displayName(Map.Entry<String, ScoreAiProperties.Model> entry) {
        return StringUtils.hasText(entry.getValue().getDisplayName())
                ? entry.getValue().getDisplayName().strip() : entry.getKey();
    }

    private String description(Map.Entry<String, ScoreAiProperties.Model> entry) {
        return StringUtils.hasText(entry.getValue().getDescription())
                ? entry.getValue().getDescription().strip() : "";
    }

    private List<ReasoningEffortDescriptor> reasoningEfforts(ScoreAiProperties.Model model) {
        List<ReasoningEffortDescriptor> efforts = model.getReasoningEfforts().stream()
                .filter(effort -> effort != null && StringUtils.hasText(effort.getName()))
                .map(effort -> new ReasoningEffortDescriptor(
                        effort.getName().strip().toLowerCase(),
                        StringUtils.hasText(effort.getDisplayName())
                                ? effort.getDisplayName().strip() : effort.getName().strip(),
                        StringUtils.hasText(effort.getDescription()) ? effort.getDescription().strip() : ""))
                .distinct()
                .toList();
        return efforts.isEmpty() ? List.of(
                new ReasoningEffortDescriptor("low", "Low", "Fast responses with lighter reasoning."),
                new ReasoningEffortDescriptor("medium", "Medium", "Balanced reasoning depth."),
                new ReasoningEffortDescriptor("high", "High", "Greater reasoning depth.")) : efforts;
    }

    private String defaultReasoningEffort(ScoreAiProperties.Model model) {
        String configured = StringUtils.hasText(model.getReasoningEffort())
                ? model.getReasoningEffort() : model.getOutputEffort();
        if (StringUtils.hasText(configured)) {
            String normalized = configured.strip().toLowerCase();
            if (reasoningEfforts(model).stream().anyMatch(effort -> effort.name().equals(normalized))) {
                return normalized;
            }
        }
        return reasoningEfforts(model).getFirst().name();
    }

    public record ModelDescriptor(String name, String displayName, String description, String provider,
                                  boolean defaultModel, String defaultReasoningEffort,
                                  List<ReasoningEffortDescriptor> reasoningEfforts,
                                  String defaultRuntime, List<RuntimeDescriptor> runtimes,
                                  ContextBudgetDescriptor contextBudget) {}

    public record ReasoningEffortDescriptor(String name, String displayName, String description) {}

    public record RuntimeDescriptor(String name, String displayName, String description) {}

    public record RuntimeModel(String name, String model, String providerType,
                               Integer maxTokens, Double temperature,
                               Integer thinkingBudgetTokens, boolean adaptiveThinking,
                               String outputEffort, String cacheStrategy,
                               List<ReasoningEffortDescriptor> reasoningEfforts,
                               Boolean configuredReasoningModel,
                               Boolean configuredOutputEffort,
                               Boolean configuredVerbosity,
                               Boolean configuredTemperature,
                               List<String> thinkingModes,
                               String defaultThinking,
                               ContextBudgetDescriptor contextBudget) {

        public RuntimeModel(String name, String model, String providerType,
                            Integer maxTokens, Double temperature,
                            Integer thinkingBudgetTokens, boolean adaptiveThinking,
                            String outputEffort, String cacheStrategy,
                            List<ReasoningEffortDescriptor> reasoningEfforts,
                            Boolean configuredReasoningModel,
                            Boolean configuredOutputEffort,
                            Boolean configuredVerbosity,
                            Boolean configuredTemperature,
                            List<String> thinkingModes,
                            String defaultThinking) {
            this(name, model, providerType, maxTokens, temperature, thinkingBudgetTokens,
                    adaptiveThinking, outputEffort, cacheStrategy, reasoningEfforts,
                    configuredReasoningModel, configuredOutputEffort, configuredVerbosity,
                    configuredTemperature, thinkingModes, defaultThinking,
                    new ContextBudgetDescriptor(null, null, null, null, null, false));
        }

        public boolean openAiReasoningModel() {
            if (!"openai".equals(providerType) && !"azure-openai".equals(providerType)) {
                return false;
            }
            if (configuredReasoningModel != null) {
                return configuredReasoningModel;
            }
            String normalized = model.toLowerCase();
            return normalized.startsWith("gpt-5") || normalized.matches("o[134](?:[-_].*)?")
                    || reasoningEfforts.stream().anyMatch(effort -> !"default".equals(effort.name()));
        }

        public boolean supportsVerbosity() {
            if (configuredVerbosity != null) {
                return configuredVerbosity;
            }
            return openAiReasoningModel() && model.toLowerCase().startsWith("gpt-5");
        }

        public boolean supportsTemperature() {
            if (configuredTemperature != null) {
                return configuredTemperature;
            }
            if (openAiReasoningModel()) {
                return false;
            }
            return !adaptiveThinking && thinkingBudgetTokens == null;
        }

        public boolean supportsOutputEffort() {
            if (configuredOutputEffort != null) {
                return configuredOutputEffort;
            }
            return adaptiveThinking || StringUtils.hasText(outputEffort);
        }

        public List<String> supportedThinkingModes() {
            if (!thinkingModes.isEmpty()) {
                return thinkingModes;
            }
            if (adaptiveThinking) {
                return List.of("adaptive", "disabled");
            }
            if (thinkingBudgetTokens != null) {
                return List.of("enabled", "disabled");
            }
            return List.of();
        }

        public String resolvedDefaultThinking() {
            if (StringUtils.hasText(defaultThinking) && supportedThinkingModes().contains(defaultThinking)) {
                return defaultThinking;
            }
            if (adaptiveThinking && supportedThinkingModes().contains("adaptive")) {
                return "adaptive";
            }
            if (thinkingBudgetTokens != null && supportedThinkingModes().contains("enabled")) {
                return "enabled";
            }
            return supportedThinkingModes().contains("disabled") ? "disabled" : null;
        }
    }

    public record ContextBudgetDescriptor(Long contextWindow, Long outputReserveTokens,
                                          Long autoCompactThresholdTokens,
                                          Long emergencyHeadroomTokens,
                                          Long toolOutputTokenLimit,
                                          boolean providerCompactionEnabled) {

        public boolean configured() {
            return contextWindow != null;
        }

        public long safeInputLimit() {
            if (!configured()) return Long.MAX_VALUE;
            return Math.max(1L, contextWindow
                    - java.util.Objects.requireNonNullElse(outputReserveTokens, 0L)
                    - java.util.Objects.requireNonNullElse(emergencyHeadroomTokens, 0L));
        }
    }
}
