package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogConfigId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelOptions;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiDatabaseCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelProfileCatalog;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.oagi.score.gateway.http.security.secret.AppSecretId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_CATALOG_CONFIG;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_REASONING_EFFORT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

public class JooqAiDatabaseCatalogRepository extends JooqBaseRepository
        implements AiDatabaseCatalogRepository {

    private final ApplicationSecretService secrets;
    private final ObjectMapper objectMapper;

    public JooqAiDatabaseCatalogRepository(DSLContext dslContext,
                                           RepositoryFactory repositoryFactory,
                                           ApplicationSecretService secrets,
                                           ObjectMapper objectMapper) {
        super(dslContext, null, repositoryFactory);
        this.secrets = secrets;
        this.objectMapper = objectMapper;
    }

    @Override
    public void loadInto(ScoreAiProperties properties) {
        Map<AiProviderId, String> providerNames = new LinkedHashMap<>();
        Map<AiProviderId, String> providerTypes = new LinkedHashMap<>();
        Map<String, ScoreAiProperties.Provider> providers = new LinkedHashMap<>();
        for (Record row : dslContext().selectFrom(AI_PROVIDER)
                .where(AI_PROVIDER.ENABLED.eq((byte) 1))
                .orderBy(AI_PROVIDER.AI_PROVIDER_ID).fetch()) {
            AppSecretId secretId = row.get(AI_PROVIDER.API_KEY_SECRET_ID) != null
                    ? new AppSecretId(row.get(
                            AI_PROVIDER.API_KEY_SECRET_ID).toBigInteger()) : null;
            if (secretId == null || !secrets.isEncryptionConfigured()) continue;
            char[] plaintext = secrets.decrypt(dslContext(), valueOf(secretId));
            try {
                ScoreAiProperties.Provider provider = new ScoreAiProperties.Provider();
                provider.setType(row.get(AI_PROVIDER.PROVIDER_TYPE));
                provider.setBaseUrl(row.get(AI_PROVIDER.BASE_URL));
                provider.setMessagesUrl(row.get(AI_PROVIDER.MESSAGES_URL));
                provider.setAnthropicVersion(row.get(AI_PROVIDER.ANTHROPIC_VERSION));
                provider.setApiVersion(row.get(AI_PROVIDER.API_VERSION));
                provider.setKey(new String(plaintext));
                String name = row.get(AI_PROVIDER.PROVIDER_NAME);
                providers.put(name, provider);
                AiProviderId providerId = new AiProviderId(
                        row.get(AI_PROVIDER.AI_PROVIDER_ID).toBigInteger());
                providerNames.put(providerId, name);
                providerTypes.put(providerId,
                        row.get(AI_PROVIDER.PROVIDER_TYPE));
            } finally {
                ApplicationSecretService.clear(plaintext);
            }
        }

        Map<String, ScoreAiProperties.Model> models = new LinkedHashMap<>();
        Map<AiModelId, String> modelKeys = new LinkedHashMap<>();
        for (Record row : dslContext().selectFrom(AI_MODEL)
                .where(AI_MODEL.ENABLED.eq((byte) 1))
                .orderBy(AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID).fetch()) {
            AiProviderId providerId = new AiProviderId(
                    row.get(AI_MODEL.PROVIDER_ID).toBigInteger());
            String providerName = providerNames.get(providerId);
            if (providerName == null) continue;
            AiModelProfile profile = AiModelProfileCatalog.find(
                    providerTypes.get(providerId),
                    row.get(AI_MODEL.MODEL_KEY)).orElse(null);
            AiModelProfileView view = profile != null ? AiModelProfileView.from(profile) : null;
            AiModelProfileView.ModelConfigurationConstraints limits = view != null
                    ? view.configurationConstraints() : null;
            AiModelProfileView.ModelCapabilityConstraints profileCapabilities = view != null
                    ? view.capabilityConstraints() : null;
            Map<String, Object> storedOptions = jsonObject(row.get(AI_MODEL.MODEL_OPTIONS_JSON));
            Map<String, Object> options = profile != null
                    ? AiModelOptions.runtimeOptions(profile, storedOptions) : Map.of();
            ScoreAiProperties.Model model = new ScoreAiProperties.Model();
            model.setCatalogId(new AiModelId(
                    row.get(AI_MODEL.AI_MODEL_ID).toBigInteger()));
            model.setProvider(providerName);
            model.setModel(row.get(AI_MODEL.PROVIDER_MODEL_NAME));
            model.setDisplayName(row.get(AI_MODEL.DISPLAY_NAME));
            model.setDescription(row.get(AI_MODEL.DESCRIPTION));
            model.setMaxTokens(number(row.get(AI_MODEL.MAX_TOKENS),
                    limits != null ? limits.maxOutputTokens().defaultValue() : null,
                    Integer.class));
            model.setContextWindow(number(row.get(AI_MODEL.CONTEXT_WINDOW), Long.class));
            model.setTemperature(decimal(options.get("temperature"),
                    limits != null ? limits.temperature().defaultValue() : null));
            model.setThinkingBudgetTokens(number(numberValue(options.get("thinkingBudgetTokens")),
                    limits != null ? limits.thinkingBudgetTokens().defaultValue() : null,
                    Integer.class));
            model.setAdaptiveThinking(flag(options.get("adaptiveThinking"), false));
            model.setOutputEffort(stringValue(options.get("outputEffort")));
            model.setCacheStrategy(cacheStrategy(options.get("cacheStrategy")));
            model.setModelOptions(options);

            ScoreAiProperties.ContextBudget budget = new ScoreAiProperties.ContextBudget();
            budget.setOutputReserveTokens(number(row.get(AI_MODEL.OUTPUT_RESERVE_TOKENS),
                    limits != null ? limits.outputReserveTokens().defaultValue() : null,
                    Long.class));
            budget.setAutoCompactThresholdTokens(number(
                    row.get(AI_MODEL.AUTO_COMPACT_THRESHOLD_TOKENS),
                    limits != null ? limits.autoCompactThresholdTokens().defaultValue() : null,
                    Long.class));
            budget.setEmergencyHeadroomTokens(number(
                    row.get(AI_MODEL.EMERGENCY_HEADROOM_TOKENS), Long.class));
            budget.setToolOutputTokenLimit(number(
                    row.get(AI_MODEL.TOOL_OUTPUT_TOKEN_LIMIT), Long.class));
            budget.setProviderCompactionEnabled(
                    flag(options.get("providerCompactionEnabled"),
                            profileCapabilities != null
                                    && profileCapabilities.providerCompaction().defaultEnabled()));
            model.setContextBudget(budget);

            ScoreAiProperties.ModelCapabilities capabilities =
                    new ScoreAiProperties.ModelCapabilities();
            capabilities.setReasoningModel(flag(options.get("reasoningModelSupported"),
                    profileCapabilities != null
                            && profileCapabilities.reasoningOptions().defaultEnabled()));
            capabilities.setOutputEffort(flag(options.get("outputEffortSupported"),
                    profileCapabilities != null
                            && profileCapabilities.outputEffort().defaultEnabled()));
            capabilities.setVerbosity(flag(options.get("verbositySupported"),
                    profileCapabilities != null
                            && profileCapabilities.verbosity().defaultEnabled()));
            capabilities.setTemperature(flag(options.get("temperatureSupported"),
                    profileCapabilities != null
                            && profileCapabilities.temperature().defaultEnabled()));
            capabilities.setThinkingModes(stringList(options.get("thinkingModes")));
            capabilities.setDefaultThinking(stringValue(options.get("defaultThinking")));
            model.setModelCapabilities(capabilities);

            AiModelId modelId = new AiModelId(row.get(AI_MODEL.AI_MODEL_ID).toBigInteger());
            List<ScoreAiProperties.ReasoningEffort> efforts = dslContext()
                    .selectFrom(AI_MODEL_REASONING_EFFORT)
                    .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(valueOf(modelId)))
                    .orderBy(AI_MODEL_REASONING_EFFORT.SORT_ORDER)
                    .fetch(effort -> {
                        ScoreAiProperties.ReasoningEffort value =
                                new ScoreAiProperties.ReasoningEffort();
                        value.setName(effort.getReasoningEffort());
                        value.setDisplayName(effort.getDisplayName());
                        value.setDescription(effort.getDescription());
                        if (effort.getDefaultEffort() == 1) {
                            model.setReasoningEffort(effort.getReasoningEffort());
                        }
                        return value;
                    });
            model.setReasoningEfforts(efforts);
            String key = row.get(AI_MODEL.MODEL_KEY);
            models.put(key, model);
            modelKeys.put(modelId, key);
        }
        String defaultKey = dslContext().select(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID)
                .from(AI_MODEL_CATALOG_CONFIG)
                .where(AI_MODEL_CATALOG_CONFIG.AI_MODEL_CATALOG_CONFIG_ID.eq(
                        org.jooq.types.UByte.valueOf(
                                AiModelCatalogConfigId.GLOBAL.value().intValueExact())))
                .fetchOptional(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID)
                .map(id -> modelKeys.get(new AiModelId(id.toBigInteger())))
                .orElseGet(() -> models.keySet().stream().findFirst().orElse(null));
        properties.setProviders(providers);
        properties.setModels(models);
        properties.setModelName(defaultKey);
    }

    private Map<String, Object> jsonObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Invalid AI model options catalog JSON.", exception);
        }
    }

    private static Boolean flag(Object value, boolean defaultValue) {
        return value instanceof Boolean configured ? configured : defaultValue;
    }

    private static Number numberValue(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String cacheStrategy(Object value) {
        String strategy = stringValue(value);
        return strategy != null && !"NONE".equalsIgnoreCase(strategy)
                ? strategy.toLowerCase(java.util.Locale.ROOT).replace('_', '-') : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Number> T number(Number value, Class<T> type) {
        if (value == null) return null;
        if (type == Integer.class) return (T) Integer.valueOf(value.intValue());
        return (T) Long.valueOf(value.longValue());
    }

    private static <T extends Number> T number(Number value, Number fallback, Class<T> type) {
        return number(value != null ? value : fallback, type);
    }

    static Double decimal(Object value, Double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        return fallback;
    }
}
