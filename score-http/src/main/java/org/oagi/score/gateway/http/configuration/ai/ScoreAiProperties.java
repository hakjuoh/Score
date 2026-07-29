package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
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
    private Gateway gateway = new Gateway();
    private MultiAgent multiAgent = new MultiAgent();
    private Memory memory = new Memory();
    private Mcp mcp = new Mcp();
    private ProviderRetry providerRetry = new ProviderRetry();
    private Middleware middleware = new Middleware();

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

    public Gateway getGateway() { return gateway; }
    public void setGateway(Gateway gateway) {
        this.gateway = gateway != null ? gateway : new Gateway();
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

    public ProviderRetry getProviderRetry() {
        return providerRetry;
    }

    public void setProviderRetry(ProviderRetry providerRetry) {
        this.providerRetry = providerRetry != null ? providerRetry : new ProviderRetry();
    }

    public Middleware getMiddleware() {
        return middleware;
    }

    public void setMiddleware(Middleware middleware) {
        this.middleware = middleware != null ? middleware : new Middleware();
    }

    /**
     * Application-level retry for transient model-provider failures. The provider
     * SDKs' internal retries are disabled so this single loop owns the backoff and
     * can narrate every attempt to the user.
     */
    public static class ProviderRetry {

        private int maxAttempts = 10;
        private Duration initialDelay = Duration.ofSeconds(2);
        private double multiplier = 2.0;
        private Duration maxDelay = Duration.ofSeconds(60);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("score.ai.provider-retry.max-attempts must be positive.");
            }
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialDelay() { return initialDelay; }
        public void setInitialDelay(Duration initialDelay) {
            if (initialDelay == null || initialDelay.isNegative()) {
                throw new IllegalArgumentException("score.ai.provider-retry.initial-delay must not be negative.");
            }
            this.initialDelay = initialDelay;
        }

        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("score.ai.provider-retry.multiplier must be at least 1.");
            }
            this.multiplier = multiplier;
        }

        public Duration getMaxDelay() { return maxDelay; }
        public void setMaxDelay(Duration maxDelay) {
            if (maxDelay == null || maxDelay.isNegative()) {
                throw new IllegalArgumentException("score.ai.provider-retry.max-delay must not be negative.");
            }
            this.maxDelay = maxDelay;
        }
    }

    /** Registered middleware selection. Configuration never names executable Java classes. */
    public static class Middleware {
        private Map<String, List<String>> profiles = new LinkedHashMap<>();
        private Map<ExecutionScope.Purpose, String> profileByPurpose = new LinkedHashMap<>();
        private Map<String, MiddlewarePolicy> policies = new LinkedHashMap<>();

        public Map<String, List<String>> getProfiles() { return profiles; }
        public void setProfiles(Map<String, List<String>> profiles) {
            this.profiles = profiles != null ? new LinkedHashMap<>(profiles) : new LinkedHashMap<>();
        }
        public Map<ExecutionScope.Purpose, String> getProfileByPurpose() { return profileByPurpose; }
        public void setProfileByPurpose(Map<ExecutionScope.Purpose, String> profileByPurpose) {
            this.profileByPurpose = profileByPurpose != null
                    ? new LinkedHashMap<>(profileByPurpose) : new LinkedHashMap<>();
        }
        public Map<String, MiddlewarePolicy> getPolicies() { return policies; }
        public void setPolicies(Map<String, MiddlewarePolicy> policies) {
            this.policies = policies != null ? new LinkedHashMap<>(policies) : new LinkedHashMap<>();
        }
    }

    public static class MiddlewarePolicy {
        private MiddlewareMode mode = MiddlewareMode.ENFORCE;
        private List<ExecutionScope.Purpose> purposes = List.of();
        private List<AiTool.ToolEffect> toolEffects = List.of();
        private Map<String, String> settings = new LinkedHashMap<>();

        public MiddlewareMode getMode() { return mode; }
        public void setMode(MiddlewareMode mode) {
            this.mode = mode != null ? mode : MiddlewareMode.ENFORCE;
        }
        public List<ExecutionScope.Purpose> getPurposes() { return purposes; }
        public void setPurposes(List<ExecutionScope.Purpose> purposes) {
            this.purposes = purposes != null ? List.copyOf(purposes) : List.of();
        }
        public List<AiTool.ToolEffect> getToolEffects() { return toolEffects; }
        public void setToolEffects(List<AiTool.ToolEffect> toolEffects) {
            this.toolEffects = toolEffects != null ? List.copyOf(toolEffects) : List.of();
        }
        public Map<String, String> getSettings() { return settings; }
        public void setSettings(Map<String, String> settings) {
            this.settings = settings != null ? new LinkedHashMap<>(settings) : new LinkedHashMap<>();
        }
    }

    public enum MiddlewareMode { ENFORCE, SHADOW, DISABLED }

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
        private ModelCapabilities modelCapabilities = new ModelCapabilities();

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
        public ModelCapabilities getModelCapabilities() { return modelCapabilities; }
        public void setModelCapabilities(ModelCapabilities modelCapabilities) {
            this.modelCapabilities = modelCapabilities != null
                    ? modelCapabilities : new ModelCapabilities();
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
    public static class ModelCapabilities {
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
        private String systemPromptResource =
                "classpath:ai/system/system-prompt-connect-center-assistant.md";

        public String getSystemPromptResource() { return systemPromptResource; }
        public void setSystemPromptResource(String systemPromptResource) {
            this.systemPromptResource = systemPromptResource;
        }
    }

    /** Trusted per-turn no-Tool simple-request Agent configuration. */
    public static class Gateway {
        private boolean enabled = true;
        private double directConfidenceThreshold = 0.90d;
        private int maximumInputCharacters = 4000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getDirectConfidenceThreshold() { return directConfidenceThreshold; }
        public void setDirectConfidenceThreshold(double value) {
            if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
                throw new IllegalArgumentException("Gateway direct confidence must be between 0 and 1.");
            }
            directConfidenceThreshold = value;
        }
        public int getMaximumInputCharacters() { return maximumInputCharacters; }
        public void setMaximumInputCharacters(int value) {
            if (value < 128 || value > 32_768) {
                throw new IllegalArgumentException("Gateway input limit must be between 128 and 32768.");
            }
            maximumInputCharacters = value;
        }
    }

    /** Process-wide fan-out admission and per-invocation inactivity detection. */
    public static class MultiAgent {
        private int maxConcurrentSpecialists = 16;
        // Both caps are per application instance; cluster-wide bounding comes from
        // the per-user active-request limit in the shared AI request registry.
        private int maxConcurrentSpecialistsPerUser = 8;
        private Duration specialistInactivityTimeout;
        private Duration specialistTimeout;
        private int maximumWorkflowIterations = 3;

        public int getMaxConcurrentSpecialists() { return maxConcurrentSpecialists; }
        public void setMaxConcurrentSpecialists(int maxConcurrentSpecialists) {
            this.maxConcurrentSpecialists = maxConcurrentSpecialists;
        }
        public int getMaxConcurrentSpecialistsPerUser() { return maxConcurrentSpecialistsPerUser; }
        public void setMaxConcurrentSpecialistsPerUser(int maxConcurrentSpecialistsPerUser) {
            this.maxConcurrentSpecialistsPerUser = maxConcurrentSpecialistsPerUser;
        }
        public Duration getSpecialistInactivityTimeout() {
            return specialistInactivityTimeout != null ? specialistInactivityTimeout
                    : specialistTimeout != null ? specialistTimeout : Duration.ofMinutes(2);
        }
        public void setSpecialistInactivityTimeout(Duration value) {
            specialistInactivityTimeout = value;
        }
        /** @deprecated use {@link #getSpecialistInactivityTimeout()}. */
        @Deprecated(forRemoval = false)
        public Duration getSpecialistTimeout() { return getSpecialistInactivityTimeout(); }
        /** @deprecated use {@link #setSpecialistInactivityTimeout(Duration)}. */
        @Deprecated(forRemoval = false)
        public void setSpecialistTimeout(Duration specialistTimeout) {
            this.specialistTimeout = specialistTimeout;
        }
        public int getMaximumWorkflowIterations() { return maximumWorkflowIterations; }
        public void setMaximumWorkflowIterations(int maximumWorkflowIterations) {
            this.maximumWorkflowIterations = maximumWorkflowIterations;
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
