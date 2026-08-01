package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves the models used by the connectCenter assistant. */
@Component
public class ScoreAiModelRegistry {

    private final ScoreAiProperties properties;
    private volatile Map<String, ChatModel> models;
    private volatile AiDynamicModelCatalog dynamicCatalog;
    private final Map<String, CatalogSnapshot> requestSnapshots = new ConcurrentHashMap<>();

    public ScoreAiModelRegistry(ScoreAiProperties properties,
                                @Qualifier("scoreAiChatModels") Map<String, ChatModel> scoreAiChatModels) {
        this.properties = properties;
        this.models = scoreAiChatModels;
    }

    @Autowired(required = false)
    void configureDynamicCatalog(AiDynamicModelCatalog dynamicCatalog) {
        this.dynamicCatalog = dynamicCatalog;
    }

    void install(Map<String, ChatModel> refreshedModels) {
        this.models = Map.copyOf(refreshedModels);
    }

    void install(Map<String, ChatModel> refreshedModels,
                 Map<String, ScoreAiProperties.Provider> providers,
                 Map<String, ScoreAiProperties.Model> configurations,
                 String defaultModel) {
        properties.setProviders(new LinkedHashMap<>(providers));
        properties.setModels(new LinkedHashMap<>(configurations));
        properties.setModelName(defaultModel);
        this.models = Map.copyOf(refreshedModels);
    }

    private void refresh() {
        AiDynamicModelCatalog catalog = dynamicCatalog;
        if (catalog != null) catalog.refreshIfChanged(this);
    }

    public void snapshot(String requestId) {
        if (!StringUtils.hasText(requestId)) return;
        refresh();
        Map<String, ModelConfiguration> configurations = new LinkedHashMap<>();
        properties.getModels().keySet().forEach(name ->
                configurations.put(name, configuredModel(name)));
        requestSnapshots.putIfAbsent(requestId,
                new CatalogSnapshot(Map.copyOf(models), Map.copyOf(configurations)));
    }

    public void clearSnapshot(String requestId) {
        if (requestId != null) requestSnapshots.remove(requestId);
    }

    public boolean isAvailable() {
        refresh();
        return properties.getModels().keySet().stream().anyMatch(this::isAvailable);
    }

    public String modelName() {
        refresh();
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
        refresh();
        String name = StringUtils.hasText(requestedModelName) ? requestedModelName.strip() : modelName();
        if (!isAvailable(name)) {
            throw new IllegalArgumentException("The requested assistant model is not available: " + name);
        }
        return name;
    }

    public String resolveReasoningEffort(String modelName, String requestedReasoningEffort) {
        refresh();
        String resolvedModelName = resolveModelName(modelName);
        ScoreAiProperties.Model model = properties.getModels().get(resolvedModelName);
        List<ReasoningEffortDescriptor> efforts = reasoningEfforts(model);
        if (efforts.isEmpty()) {
            if (!StringUtils.hasText(requestedReasoningEffort)) return null;
            throw new IllegalArgumentException(
                    "The model does not support reasoning effort: " + resolvedModelName);
        }
        String normalizedRequested = StringUtils.hasText(requestedReasoningEffort)
                ? requestedReasoningEffort.strip().toLowerCase() : defaultReasoningEffort(model);
        boolean legacyDisabled = "none".equals(normalizedRequested)
                && efforts.stream().anyMatch(effort -> "disabled".equals(effort.name()));
        String requested = legacyDisabled ? "disabled" : normalizedRequested;
        return efforts.stream()
                .map(ReasoningEffortDescriptor::name)
                .filter(effort -> effort.equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The requested reasoning effort is not available for model '"
                                + resolvedModelName + "': " + requested));
    }

    public String resolveReasoningEffort(String modelName, String requestId,
                                         String requestedReasoningEffort) {
        ModelConfiguration configuration = modelConfiguration(modelName, requestId);
        if (configuration.reasoningEfforts().isEmpty()) {
            if (!StringUtils.hasText(requestedReasoningEffort)) return null;
            throw new IllegalArgumentException(
                    "The model does not support reasoning effort: " + modelName);
        }
        String normalized = StringUtils.hasText(requestedReasoningEffort)
                ? requestedReasoningEffort.strip().toLowerCase()
                : configuration.defaultReasoningEffort();
        boolean legacyDisabled = "none".equals(normalized)
                && configuration.reasoningEfforts().stream()
                .anyMatch(effort -> "disabled".equals(effort.name()));
        String requested = legacyDisabled ? "disabled" : normalized;
        return configuration.reasoningEfforts().stream()
                .map(ReasoningEffortDescriptor::name)
                .filter(effort -> effort.equalsIgnoreCase(requested))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "The requested reasoning effort is not available for model '"
                                + modelName + "': " + requested));
    }

    public List<ModelDescriptor> availableModels() {
        refresh();
        String defaultModel = isAvailable() ? modelName() : null;
        return properties.getModels().entrySet().stream()
                .filter(entry -> isAvailable(entry.getKey()))
                .map(entry -> new ModelDescriptor(entry.getKey(), displayName(entry), description(entry),
                        entry.getValue().getProvider(), entry.getKey().equals(defaultModel),
                        defaultReasoningEffort(entry.getValue()), reasoningEfforts(entry.getValue()),
                        contextBudget(entry.getValue())))
                .toList();
    }

    public ChatClient.Builder clientBuilder(String modelName) {
        refresh();
        ChatModel model = models.get(resolveModelName(modelName));
        if (model == null) {
            throw new IllegalStateException("The assistant model is not configured.");
        }
        return ChatClient.builder(model).defaultAdvisors(new VisibleTextResultAdvisor());
    }

    public ChatClient.Builder clientBuilder(String modelName, String requestId) {
        CatalogSnapshot snapshot = requestSnapshots.get(requestId);
        if (snapshot == null) return clientBuilder(modelName);
        ChatModel model = snapshot.clients().get(modelName);
        if (model == null) throw new IllegalStateException(
                "The snapshotted assistant model is not configured.");
        return ChatClient.builder(model).defaultAdvisors(new VisibleTextResultAdvisor());
    }

    public ModelConfiguration modelConfiguration(String modelName) {
        refresh();
        return configuredModel(resolveModelName(modelName));
    }

    public ModelConfiguration modelConfiguration(String modelName, String requestId) {
        CatalogSnapshot snapshot = requestSnapshots.get(requestId);
        if (snapshot == null) return modelConfiguration(modelName);
        ModelConfiguration configuration = snapshot.configurations().get(modelName);
        if (configuration == null) throw new IllegalStateException(
                "The snapshotted assistant model configuration is unavailable.");
        return configuration;
    }

    private ModelConfiguration configuredModel(String resolvedModelName) {
        ScoreAiProperties.Model model = properties.getModels().get(resolvedModelName);
        ScoreAiProperties.Provider provider = properties.getProviders().get(model.getProvider());
        ScoreAiProperties.ModelCapabilities capabilities = model.getModelCapabilities();
        String configuredModel = StringUtils.hasText(model.getModel())
                ? model.getModel().strip() : resolvedModelName;
        return new ModelConfiguration(model.getCatalogId(), resolvedModelName, configuredModel, providerType(provider),
                model.getMaxTokens(), model.getTemperature(), model.getThinkingBudgetTokens(),
                model.isAdaptiveThinking(), model.getOutputEffort(), model.getCacheStrategy(),
                model.getModelOptions(),
                reasoningEfforts(model), defaultReasoningEffort(model),
                capabilities.getReasoningModel(),
                capabilities.getOutputEffort(), capabilities.getVerbosity(),
                capabilities.getTemperature(), capabilities.getThinkingModes().stream()
                        .filter(StringUtils::hasText).map(value -> value.strip().toLowerCase()).distinct().toList(),
                StringUtils.hasText(capabilities.getDefaultThinking())
                        ? capabilities.getDefaultThinking().strip().toLowerCase() : null,
                contextBudget(model));
    }

    private record CatalogSnapshot(Map<String, ChatModel> clients,
                                   Map<String, ModelConfiguration> configurations) {}

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
        return provider != null
                && (StringUtils.hasText(provider.getBaseUrl()) || StringUtils.hasText(provider.getMessagesUrl()));
    }

    private String providerType(ScoreAiProperties.Provider provider) {
        return StringUtils.hasText(provider.getType())
                ? provider.getType().strip().toLowerCase() : "anthropic";
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
        List<ReasoningEffortDescriptor> configuredEfforts = model.getReasoningEfforts().stream()
                .filter(effort -> effort != null && StringUtils.hasText(effort.getName()))
                .map(effort -> {
                    String name = canonicalReasoningEffortName(effort.getName());
                    String displayName = "disabled".equals(name) ? "Disabled"
                            : StringUtils.hasText(effort.getDisplayName())
                            ? effort.getDisplayName().strip() : effort.getName().strip();
                    return new ReasoningEffortDescriptor(name, displayName,
                            StringUtils.hasText(effort.getDescription()) ? effort.getDescription().strip() : "");
                })
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(ReasoningEffortDescriptor::name, Function.identity(),
                                (first, ignored) -> first, LinkedHashMap::new),
                        values -> List.copyOf(values.values())));
        List<ReasoningEffortDescriptor> efforts = new ArrayList<>(configuredEfforts);
        if (!configuredEfforts.isEmpty()
                && (Boolean.TRUE.equals(model.getModelCapabilities().getReasoningModel())
                || model.getModelCapabilities().getThinkingModes().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.strip().toLowerCase())
                .anyMatch("disabled"::equals))
                && efforts.stream().noneMatch(effort -> "disabled".equals(effort.name()))) {
            efforts.addFirst(new ReasoningEffortDescriptor(
                    "disabled", "Disabled", "Disable additional reasoning."));
        }
        return List.copyOf(efforts);
    }

    private String canonicalReasoningEffortName(String name) {
        String normalized = name.strip().toLowerCase();
        return "none".equals(normalized) ? "disabled" : normalized;
    }

    private String defaultReasoningEffort(ScoreAiProperties.Model model) {
        String configured = StringUtils.hasText(model.getReasoningEffort())
                ? model.getReasoningEffort() : model.getOutputEffort();
        if (StringUtils.hasText(configured)) {
            String normalized = canonicalReasoningEffortName(configured);
            if (reasoningEfforts(model).stream().anyMatch(effort -> effort.name().equals(normalized))) {
                return normalized;
            }
        }
        List<ReasoningEffortDescriptor> efforts = reasoningEfforts(model);
        return efforts.isEmpty() ? null : efforts.getFirst().name();
    }

    public record ModelDescriptor(String name, String displayName, String description, String provider,
                                  boolean defaultModel, String defaultReasoningEffort,
                                  List<ReasoningEffortDescriptor> reasoningEfforts,
                                  ContextBudgetDescriptor contextBudget) {}

    public record ReasoningEffortDescriptor(String name, String displayName, String description) {}

    public record ModelConfiguration(AiModelId catalogId, String name, String model,
                               String providerType,
                               Integer maxTokens, Double temperature,
                               Integer thinkingBudgetTokens, boolean adaptiveThinking,
                               String outputEffort, String cacheStrategy,
                               Map<String, Object> modelOptions,
                               List<ReasoningEffortDescriptor> reasoningEfforts,
                               String defaultReasoningEffort,
                               Boolean configuredReasoningModel,
                               Boolean configuredOutputEffort,
                               Boolean configuredVerbosity,
                               Boolean configuredTemperature,
                               List<String> thinkingModes,
                               String defaultThinking,
                               ContextBudgetDescriptor contextBudget) {

        public ModelConfiguration {
            modelOptions = modelOptions != null ? Map.copyOf(modelOptions) : Map.of();
        }

        public ModelConfiguration(String name, String model, String providerType,
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
            this(null, name, model, providerType, maxTokens, temperature, thinkingBudgetTokens,
                    adaptiveThinking, outputEffort, cacheStrategy, Map.of(), reasoningEfforts,
                    reasoningEfforts != null && !reasoningEfforts.isEmpty()
                            ? reasoningEfforts.getFirst().name() : null,
                    configuredReasoningModel, configuredOutputEffort, configuredVerbosity,
                    configuredTemperature, thinkingModes, defaultThinking,
                    new ContextBudgetDescriptor(null, null, null, null, null, false));
        }

        public ModelConfiguration(String name, String model, String providerType,
                                  Integer maxTokens, Double temperature,
                                  Integer thinkingBudgetTokens, boolean adaptiveThinking,
                                  String outputEffort, String cacheStrategy,
                                  Map<String, Object> modelOptions,
                                  List<ReasoningEffortDescriptor> reasoningEfforts,
                                  Boolean configuredReasoningModel,
                                  Boolean configuredOutputEffort,
                                  Boolean configuredVerbosity,
                                  Boolean configuredTemperature,
                                  List<String> thinkingModes,
                                  String defaultThinking) {
            this(null, name, model, providerType, maxTokens, temperature, thinkingBudgetTokens,
                    adaptiveThinking, outputEffort, cacheStrategy, modelOptions, reasoningEfforts,
                    reasoningEfforts != null && !reasoningEfforts.isEmpty()
                            ? reasoningEfforts.getFirst().name() : null,
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
