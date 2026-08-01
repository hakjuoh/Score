package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application settings for the assistant model, memory, and tool integrations. */
@ConfigurationProperties("score.ai")
public class ScoreAiProperties {

    private static final Duration DEFAULT_REQUEST_INACTIVITY_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_ELICITATION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_CHANGE_APPROVAL_TIMEOUT = Duration.ofMinutes(10);

    private Map<String, Provider> providers = new LinkedHashMap<>();
    private Map<String, Model> models = new LinkedHashMap<>();
    private String modelName;
    private Duration requestTimeout;
    private Duration requestInactivityTimeout;
    private Duration elicitationTimeout;
    private Duration changeApprovalTimeout;
    private Assistant assistant = new Assistant();
    private Gateway gateway = new Gateway();
    private MultiAgent multiAgent = new MultiAgent();
    private Memory memory = new Memory();
    private Tools tools = new Tools();
    private ProviderRetry providerRetry = new ProviderRetry();
    private Middleware middleware = new Middleware();
    private Quota quota = new Quota();
    private Policy policy = new Policy();

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public Policy getPolicy() { return policy; }
    public void setPolicy(Policy policy) {
        this.policy = policy != null ? policy : new Policy();
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

    /** Legacy shared timeout retained as a fallback for existing deployments. */
    @Deprecated
    public Duration getRequestTimeout() {
        return requestTimeout != null ? requestTimeout : getRequestInactivityTimeout();
    }

    @Deprecated
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getRequestInactivityTimeout() {
        return requestInactivityTimeout != null ? requestInactivityTimeout
                : requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_INACTIVITY_TIMEOUT;
    }

    public void setRequestInactivityTimeout(Duration requestInactivityTimeout) {
        this.requestInactivityTimeout = requestInactivityTimeout;
    }

    public Duration getElicitationTimeout() {
        return elicitationTimeout != null ? elicitationTimeout
                : requestTimeout != null ? requestTimeout : DEFAULT_ELICITATION_TIMEOUT;
    }

    public void setElicitationTimeout(Duration elicitationTimeout) {
        this.elicitationTimeout = elicitationTimeout;
    }

    public Duration getChangeApprovalTimeout() {
        return changeApprovalTimeout != null ? changeApprovalTimeout
                : requestTimeout != null ? requestTimeout : DEFAULT_CHANGE_APPROVAL_TIMEOUT;
    }

    public void setChangeApprovalTimeout(Duration changeApprovalTimeout) {
        this.changeApprovalTimeout = changeApprovalTimeout;
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

    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota != null ? quota : new Quota(); }

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

    public Tools getTools() { return tools; }
    public void setTools(Tools tools) {
        this.tools = tools != null ? tools : new Tools();
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

    public static class Provider {
        private String type;
        private String baseUrl;
        private String messagesUrl;
        private String key;
        private String apiVersion;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getMessagesUrl() { return messagesUrl; }
        public void setMessagesUrl(String messagesUrl) { this.messagesUrl = messagesUrl; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }

    public static class Model {
        private AiModelId catalogId;
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
        private Map<String, Object> modelOptions = new LinkedHashMap<>();
        private ModelCapabilities modelCapabilities = new ModelCapabilities();

        public AiModelId getCatalogId() { return catalogId; }
        public void setCatalogId(AiModelId catalogId) { this.catalogId = catalogId; }
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
        public Map<String, Object> getModelOptions() { return modelOptions; }
        public void setModelOptions(Map<String, Object> modelOptions) {
            this.modelOptions = modelOptions != null
                    ? new LinkedHashMap<>(modelOptions) : new LinkedHashMap<>();
        }
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

    public static class Quota {
        private int inputSafetyPercent = 10;
        private Duration reservationTimeout = Duration.ofMinutes(15);
        private int reconciliationBatchSize = 200;

        public int getInputSafetyPercent() { return inputSafetyPercent; }
        public void setInputSafetyPercent(int inputSafetyPercent) {
            if (inputSafetyPercent < 0 || inputSafetyPercent > 100) {
                throw new IllegalArgumentException(
                        "AI quota input safety percent must be between 0 and 100.");
            }
            this.inputSafetyPercent = inputSafetyPercent;
        }
        public Duration getReservationTimeout() { return reservationTimeout; }
        public void setReservationTimeout(Duration reservationTimeout) {
            if (reservationTimeout == null || reservationTimeout.isNegative()
                    || reservationTimeout.isZero()) {
                throw new IllegalArgumentException("AI quota reservation timeout must be positive.");
            }
            this.reservationTimeout = reservationTimeout;
        }
        public int getReconciliationBatchSize() { return reconciliationBatchSize; }
        public void setReconciliationBatchSize(int reconciliationBatchSize) {
            if (reconciliationBatchSize < 1 || reconciliationBatchSize > 10_000) {
                throw new IllegalArgumentException(
                        "AI quota reconciliation batch size must be between 1 and 10000.");
            }
            this.reconciliationBatchSize = reconciliationBatchSize;
        }
    }

    public static class Policy {
        private boolean enabled = true;
        private boolean quotaEnforcementEnabled;
        private boolean usageLedgerEnabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isQuotaEnforcementEnabled() { return quotaEnforcementEnabled; }
        public void setQuotaEnforcementEnabled(boolean value) { quotaEnforcementEnabled = value; }
        public boolean isUsageLedgerEnabled() { return usageLedgerEnabled; }
        public void setUsageLedgerEnabled(boolean value) { usageLedgerEnabled = value; }
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

    /** Independently configurable tool integrations. */
    public static class Tools {
        private Files files = new Files();
        private ToolSearch toolSearch = new ToolSearch();
        private Mcp connectCenterMcp = new Mcp();

        public Files getFiles() { return files; }
        public void setFiles(Files files) {
            this.files = files != null ? files : new Files();
        }
        public ToolSearch getToolSearch() { return toolSearch; }
        public void setToolSearch(ToolSearch toolSearch) {
            this.toolSearch = toolSearch != null ? toolSearch : new ToolSearch();
        }
        public Mcp getConnectCenterMcp() { return connectCenterMcp; }
        public void setConnectCenterMcp(Mcp connectCenterMcp) {
            this.connectCenterMcp = connectCenterMcp != null ? connectCenterMcp : new Mcp();
        }
    }

    /** Deferred tool discovery. Disabled mode exposes the complete callback registry. */
    public static class ToolSearch {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** The create_file tool, including rendering, storage, and retention. */
    public static class Files {
        private boolean enabled = true;
        private Duration retention = Duration.ofDays(7);
        private DataSize maxBytes = DataSize.ofMegabytes(20);
        private FileStorage storage = new FileStorage();
        private FileRendering rendering = new FileRendering();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) {
            this.retention = retention != null ? retention : Duration.ofDays(7);
        }
        public DataSize getMaxBytes() { return maxBytes; }
        public void setMaxBytes(DataSize maxBytes) {
            this.maxBytes = maxBytes != null ? maxBytes : DataSize.ofMegabytes(20);
        }
        public FileStorage getStorage() { return storage; }
        public void setStorage(FileStorage storage) {
            this.storage = storage != null ? storage : new FileStorage();
        }
        public FileRendering getRendering() { return rendering; }
        public void setRendering(FileRendering rendering) {
            this.rendering = rendering != null ? rendering : new FileRendering();
        }
    }

    public static class FileStorage {
        private String provider = "local";
        private LocalFileStorage local = new LocalFileStorage();
        private S3FileStorage s3 = new S3FileStorage();
        private GoogleDriveFileStorage googleDrive = new GoogleDriveFileStorage();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public LocalFileStorage getLocal() { return local; }
        public void setLocal(LocalFileStorage local) {
            this.local = local != null ? local : new LocalFileStorage();
        }
        public S3FileStorage getS3() { return s3; }
        public void setS3(S3FileStorage s3) {
            this.s3 = s3 != null ? s3 : new S3FileStorage();
        }
        public GoogleDriveFileStorage getGoogleDrive() { return googleDrive; }
        public void setGoogleDrive(GoogleDriveFileStorage googleDrive) {
            this.googleDrive = googleDrive != null ? googleDrive : new GoogleDriveFileStorage();
        }
    }

    public static class LocalFileStorage {
        private String rootDirectory = "./data/ai-files";
        public String getRootDirectory() { return rootDirectory; }
        public void setRootDirectory(String rootDirectory) { this.rootDirectory = rootDirectory; }
    }

    public static class S3FileStorage {
        private String bucket;
        private String prefix = "ai-files";
        private String region = "us-east-1";
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private boolean pathStyleAccess;
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
    }

    public static class GoogleDriveFileStorage {
        private String accessToken;
        private String folderId;
        private String apiBaseUrl = "https://www.googleapis.com";
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getFolderId() { return folderId; }
        public void setFolderId(String folderId) { this.folderId = folderId; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    }

    public static class FileRendering {
        private String assetBaseUri;
        private List<String> fontFiles = List.of();
        private DataSize maxExternalAssetBytes = DataSize.ofMegabytes(5);
        public String getAssetBaseUri() { return assetBaseUri; }
        public void setAssetBaseUri(String assetBaseUri) { this.assetBaseUri = assetBaseUri; }
        public List<String> getFontFiles() { return fontFiles; }
        public void setFontFiles(List<String> fontFiles) {
            this.fontFiles = fontFiles != null ? List.copyOf(fontFiles) : List.of();
        }
        public DataSize getMaxExternalAssetBytes() { return maxExternalAssetBytes; }
        public void setMaxExternalAssetBytes(DataSize maxExternalAssetBytes) {
            this.maxExternalAssetBytes = maxExternalAssetBytes != null
                    ? maxExternalAssetBytes : DataSize.ofMegabytes(5);
        }
    }

    public static class Mcp {
        private String connectionName = "connect-center-mcp";

        public String getConnectionName() { return connectionName; }
        public void setConnectionName(String connectionName) { this.connectionName = connectionName; }
    }
}
