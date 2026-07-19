package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application settings for the assistant model, memory, and MCP connection. */
@ConfigurationProperties("score.ai")
public class ScoreAiProperties {

    private Map<String, Provider> providers = new LinkedHashMap<>();
    private Map<String, Model> models = new LinkedHashMap<>();
    private String modelName;
    private Duration requestTimeout = Duration.ofMinutes(10);
    private Assistant assistant = new Assistant();
    private MultiAgent multiAgent = new MultiAgent();
    private Memory memory = new Memory();
    private Mcp mcp = new Mcp();

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers != null ? providers : new LinkedHashMap<>();
    }

    public Map<String, Model> getModels() {
        return models;
    }

    public void setModels(Map<String, Model> models) {
        this.models = models != null ? models : new LinkedHashMap<>();
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofMinutes(10);
    }

    public Assistant getAssistant() {
        return assistant;
    }

    public void setAssistant(Assistant assistant) {
        this.assistant = assistant != null ? assistant : new Assistant();
    }

    public MultiAgent getMultiAgent() {
        return multiAgent;
    }

    public void setMultiAgent(MultiAgent multiAgent) {
        this.multiAgent = multiAgent != null ? multiAgent : new MultiAgent();
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory != null ? memory : new Memory();
    }

    public Mcp getMcp() {
        return mcp;
    }

    public void setMcp(Mcp mcp) {
        this.mcp = mcp != null ? mcp : new Mcp();
    }

    public static class Provider {
        private String type;
        private String baseUrl;
        private String messagesUrl;
        private String key;
        private String anthropicVersion;
        private String apiVersion;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getMessagesUrl() { return messagesUrl; }
        public void setMessagesUrl(String messagesUrl) { this.messagesUrl = messagesUrl; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getAnthropicVersion() { return anthropicVersion; }
        public void setAnthropicVersion(String anthropicVersion) { this.anthropicVersion = anthropicVersion; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }

    public static class Model {
        private String displayName;
        private String description;
        private String provider;
        private String model;
        private String reasoningEffort;
        private List<ReasoningEffort> reasoningEfforts = List.of();
        private Integer maxTokens;
        private Long contextWindow;
        private ContextBudget contextBudget = new ContextBudget();
        private Double temperature;
        private Integer thinkingBudgetTokens;
        private boolean adaptiveThinking;
        private String outputEffort;
        private String cacheStrategy;
        private List<String> runtimes = List.of();
        private RuntimeCapabilities runtimeCapabilities = new RuntimeCapabilities();

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
        public List<ReasoningEffort> getReasoningEfforts() { return reasoningEfforts; }
        public void setReasoningEfforts(List<ReasoningEffort> reasoningEfforts) {
            this.reasoningEfforts = reasoningEfforts != null ? List.copyOf(reasoningEfforts) : List.of();
        }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public Long getContextWindow() { return contextWindow; }
        public void setContextWindow(Long contextWindow) { this.contextWindow = contextWindow; }
        public ContextBudget getContextBudget() { return contextBudget; }
        public void setContextBudget(ContextBudget contextBudget) {
            this.contextBudget = contextBudget != null ? contextBudget : new ContextBudget();
        }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getThinkingBudgetTokens() { return thinkingBudgetTokens; }
        public void setThinkingBudgetTokens(Integer thinkingBudgetTokens) { this.thinkingBudgetTokens = thinkingBudgetTokens; }
        public boolean isAdaptiveThinking() { return adaptiveThinking; }
        public void setAdaptiveThinking(boolean adaptiveThinking) { this.adaptiveThinking = adaptiveThinking; }
        public String getOutputEffort() { return outputEffort; }
        public void setOutputEffort(String outputEffort) { this.outputEffort = outputEffort; }
        public String getCacheStrategy() { return cacheStrategy; }
        public void setCacheStrategy(String cacheStrategy) { this.cacheStrategy = cacheStrategy; }
        public List<String> getRuntimes() { return runtimes; }
        public void setRuntimes(List<String> runtimes) {
            this.runtimes = runtimes != null ? List.copyOf(runtimes) : List.of();
        }
        public RuntimeCapabilities getRuntimeCapabilities() { return runtimeCapabilities; }
        public void setRuntimeCapabilities(RuntimeCapabilities runtimeCapabilities) {
            this.runtimeCapabilities = runtimeCapabilities != null
                    ? runtimeCapabilities : new RuntimeCapabilities();
        }
    }

    /** Per-model guardrails for estimating, displaying, and compacting the active context. */
    public static class ContextBudget {
        private Long outputReserveTokens;
        private Long autoCompactThresholdTokens;
        private Long emergencyHeadroomTokens = 4096L;
        private Long toolOutputTokenLimit = 32000L;
        private boolean providerCompactionEnabled = true;

        public Long getOutputReserveTokens() { return outputReserveTokens; }
        public void setOutputReserveTokens(Long outputReserveTokens) {
            this.outputReserveTokens = outputReserveTokens;
        }
        public Long getAutoCompactThresholdTokens() { return autoCompactThresholdTokens; }
        public void setAutoCompactThresholdTokens(Long autoCompactThresholdTokens) {
            this.autoCompactThresholdTokens = autoCompactThresholdTokens;
        }
        public Long getEmergencyHeadroomTokens() { return emergencyHeadroomTokens; }
        public void setEmergencyHeadroomTokens(Long emergencyHeadroomTokens) {
            this.emergencyHeadroomTokens = emergencyHeadroomTokens;
        }
        public Long getToolOutputTokenLimit() { return toolOutputTokenLimit; }
        public void setToolOutputTokenLimit(Long toolOutputTokenLimit) {
            this.toolOutputTokenLimit = toolOutputTokenLimit;
        }
        public boolean isProviderCompactionEnabled() { return providerCompactionEnabled; }
        public void setProviderCompactionEnabled(boolean providerCompactionEnabled) {
            this.providerCompactionEnabled = providerCompactionEnabled;
        }
    }

    /** Explicit provider/model feature gates; null values retain conservative legacy inference. */
    public static class RuntimeCapabilities {
        private Boolean reasoningModel;
        private Boolean outputEffort;
        private Boolean verbosity;
        private Boolean temperature;
        private List<String> thinkingModes = List.of();
        private String defaultThinking;

        public Boolean getReasoningModel() { return reasoningModel; }
        public void setReasoningModel(Boolean reasoningModel) { this.reasoningModel = reasoningModel; }
        public Boolean getOutputEffort() { return outputEffort; }
        public void setOutputEffort(Boolean outputEffort) { this.outputEffort = outputEffort; }
        public Boolean getVerbosity() { return verbosity; }
        public void setVerbosity(Boolean verbosity) { this.verbosity = verbosity; }
        public Boolean getTemperature() { return temperature; }
        public void setTemperature(Boolean temperature) { this.temperature = temperature; }
        public List<String> getThinkingModes() { return thinkingModes; }
        public void setThinkingModes(List<String> thinkingModes) {
            this.thinkingModes = thinkingModes != null ? List.copyOf(thinkingModes) : List.of();
        }
        public String getDefaultThinking() { return defaultThinking; }
        public void setDefaultThinking(String defaultThinking) { this.defaultThinking = defaultThinking; }
    }

    public static class ReasoningEffort {
        private String name;
        private String displayName;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class Assistant {
        private String systemPromptResource = "classpath:prompts/connect-center-assistant-system-prompt.md";

        public String getSystemPromptResource() { return systemPromptResource; }
        public void setSystemPromptResource(String systemPromptResource) {
            this.systemPromptResource = systemPromptResource;
        }
    }

    /** Process-wide fan-out admission and one shared deadline for each specialist wave. */
    public static class MultiAgent {
        private int maxConcurrentSpecialists = 16;
        // Both caps are per application instance; cluster-wide bounding comes from
        // the per-user active-request limit in the shared AI request registry.
        private int maxConcurrentSpecialistsPerUser = 8;
        private Duration specialistTimeout = Duration.ofMinutes(2);

        public int getMaxConcurrentSpecialists() { return maxConcurrentSpecialists; }
        public void setMaxConcurrentSpecialists(int maxConcurrentSpecialists) {
            this.maxConcurrentSpecialists = maxConcurrentSpecialists;
        }
        public int getMaxConcurrentSpecialistsPerUser() { return maxConcurrentSpecialistsPerUser; }
        public void setMaxConcurrentSpecialistsPerUser(int maxConcurrentSpecialistsPerUser) {
            this.maxConcurrentSpecialistsPerUser = maxConcurrentSpecialistsPerUser;
        }
        public Duration getSpecialistTimeout() { return specialistTimeout; }
        public void setSpecialistTimeout(Duration specialistTimeout) {
            this.specialistTimeout = specialistTimeout != null
                    ? specialistTimeout : Duration.ofMinutes(2);
        }
    }

    public static class Memory {
        private int maxMessages = 40;
        private Duration retention = Duration.ofDays(90);
        private Duration cleanupInterval = Duration.ofDays(1);

        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) {
            this.retention = retention != null ? retention : Duration.ofDays(90);
        }
        public Duration getCleanupInterval() { return cleanupInterval; }
        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval != null ? cleanupInterval : Duration.ofDays(1);
        }
    }

    public static class Mcp {
        private String connectionName = "connect-center-mcp";
        private Duration requestTimeout = Duration.ofSeconds(60);
        private Duration initializationTimeout = Duration.ofSeconds(20);
        private Auth auth = new Auth();

        public String getConnectionName() { return connectionName; }
        public void setConnectionName(String connectionName) { this.connectionName = connectionName; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
        public Duration getInitializationTimeout() { return initializationTimeout; }
        public void setInitializationTimeout(Duration initializationTimeout) { this.initializationTimeout = initializationTimeout; }
        public Auth getAuth() { return auth; }
        public void setAuth(Auth auth) { this.auth = auth != null ? auth : new Auth(); }
    }

    public static class Auth {
        private String bearerToken;
        private String issuerUrl;
        private String audience = "connect-center-mcp";
        private String algorithm = "ES256";
        private long tokenTtlSeconds = 300;

        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
        public String getIssuerUrl() { return issuerUrl; }
        public void setIssuerUrl(String issuerUrl) { this.issuerUrl = issuerUrl; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public long getTokenTtlSeconds() { return tokenTtlSeconds; }
        public void setTokenTtlSeconds(long tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }
    }
}
