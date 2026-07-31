package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_CATALOG_CONFIG;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_REASONING_EFFORT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

/** Replaces legacy YAML catalog values with the enabled database catalog at startup. */
public final class AiDatabaseCatalogLoader {

    private final DSLContext dsl;
    private final ApplicationSecretService secrets;
    private final ObjectMapper objectMapper;

    public AiDatabaseCatalogLoader(DSLContext dsl, ApplicationSecretService secrets,
                                   ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.secrets = secrets;
        this.objectMapper = objectMapper;
    }

    public void loadInto(ScoreAiProperties properties) {
        Map<ULong, String> providerNames = new LinkedHashMap<>();
        Map<String, ScoreAiProperties.Provider> providers = new LinkedHashMap<>();
        for (Record row : dsl.selectFrom(AI_PROVIDER)
                .where(AI_PROVIDER.ENABLED.eq((byte) 1))
                .orderBy(AI_PROVIDER.AI_PROVIDER_ID).fetch()) {
            ULong secretId = row.get(AI_PROVIDER.API_KEY_SECRET_ID);
            if (secretId == null) continue;
            if (!secrets.isEncryptionConfigured()) continue;
            char[] plaintext = secrets.decrypt(dsl, secretId);
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
                providerNames.put(row.get(AI_PROVIDER.AI_PROVIDER_ID), name);
            } finally {
                ApplicationSecretService.clear(plaintext);
            }
        }

        Map<String, ScoreAiProperties.Model> models = new LinkedHashMap<>();
        Map<ULong, String> modelKeys = new LinkedHashMap<>();
        for (Record row : dsl.selectFrom(AI_MODEL)
                .where(AI_MODEL.ENABLED.eq((byte) 1))
                .orderBy(AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID).fetch()) {
            String providerName = providerNames.get(row.get(AI_MODEL.PROVIDER_ID));
            if (providerName == null) continue;
            ScoreAiProperties.Model model = new ScoreAiProperties.Model();
            model.setCatalogId(row.get(AI_MODEL.AI_MODEL_ID).longValue());
            model.setProvider(providerName);
            model.setModel(row.get(AI_MODEL.PROVIDER_MODEL_NAME));
            model.setDisplayName(row.get(AI_MODEL.DISPLAY_NAME));
            model.setDescription(row.get(AI_MODEL.DESCRIPTION));
            model.setMaxTokens(number(row.get(AI_MODEL.MAX_TOKENS), Integer.class));
            model.setContextWindow(number(row.get(AI_MODEL.CONTEXT_WINDOW), Long.class));
            model.setTemperature(row.get(AI_MODEL.TEMPERATURE) != null
                    ? row.get(AI_MODEL.TEMPERATURE).doubleValue() : null);
            model.setThinkingBudgetTokens(number(row.get(AI_MODEL.THINKING_BUDGET_TOKENS),
                    Integer.class));
            model.setAdaptiveThinking(row.get(AI_MODEL.ADAPTIVE_THINKING) == 1);
            model.setOutputEffort(row.get(AI_MODEL.OUTPUT_EFFORT));
            model.setCacheStrategy(row.get(AI_MODEL.CACHE_STRATEGY));

            ScoreAiProperties.ContextBudget budget = new ScoreAiProperties.ContextBudget();
            budget.setOutputReserveTokens(number(row.get(AI_MODEL.OUTPUT_RESERVE_TOKENS), Long.class));
            budget.setAutoCompactThresholdTokens(number(
                    row.get(AI_MODEL.AUTO_COMPACT_THRESHOLD_TOKENS), Long.class));
            budget.setEmergencyHeadroomTokens(number(
                    row.get(AI_MODEL.EMERGENCY_HEADROOM_TOKENS), Long.class));
            budget.setToolOutputTokenLimit(number(row.get(AI_MODEL.TOOL_OUTPUT_TOKEN_LIMIT), Long.class));
            budget.setProviderCompactionEnabled(row.get(AI_MODEL.PROVIDER_COMPACTION_ENABLED) == 1);
            model.setContextBudget(budget);

            ScoreAiProperties.ModelCapabilities capabilities =
                    new ScoreAiProperties.ModelCapabilities();
            capabilities.setReasoningModel(flag(row.get(AI_MODEL.REASONING_MODEL_SUPPORTED)));
            capabilities.setOutputEffort(flag(row.get(AI_MODEL.OUTPUT_EFFORT_SUPPORTED)));
            capabilities.setVerbosity(flag(row.get(AI_MODEL.VERBOSITY_SUPPORTED)));
            capabilities.setTemperature(flag(row.get(AI_MODEL.TEMPERATURE_SUPPORTED)));
            capabilities.setThinkingModes(jsonList(row.get(AI_MODEL.THINKING_MODES_JSON)));
            capabilities.setDefaultThinking(row.get(AI_MODEL.DEFAULT_THINKING));
            model.setModelCapabilities(capabilities);

            ULong modelId = row.get(AI_MODEL.AI_MODEL_ID);
            List<ScoreAiProperties.ReasoningEffort> efforts = dsl
                    .selectFrom(AI_MODEL_REASONING_EFFORT)
                    .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(modelId))
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
        String defaultKey = dsl.select(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID)
                .from(AI_MODEL_CATALOG_CONFIG)
                .where(AI_MODEL_CATALOG_CONFIG.AI_MODEL_CATALOG_CONFIG_ID.eq(
                        org.jooq.types.UByte.valueOf(1)))
                .fetchOptional(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID)
                .map(modelKeys::get).orElseGet(() -> models.keySet().stream().findFirst().orElse(null));
        properties.setProviders(providers);
        properties.setModels(models);
        properties.setModelName(defaultKey);
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid AI model thinking mode catalog JSON.", exception);
        }
    }

    private static Boolean flag(Byte value) {
        return value != null ? value == 1 : null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Number> T number(Number value, Class<T> type) {
        if (value == null) return null;
        if (type == Integer.class) return (T) Integer.valueOf(value.intValue());
        return (T) Long.valueOf(value.longValue());
    }
}
