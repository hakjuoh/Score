package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.UByte;
import org.jooq.types.UInteger;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogConfigId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiCatalogBootstrapRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_CATALOG_CONFIG;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_REASONING_EFFORT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

/** jOOQ transaction boundary for the one-time legacy AI catalog import. */
public class JooqAiCatalogBootstrapRepository extends JooqBaseRepository
        implements AiCatalogBootstrapRepository {

    private final ApplicationSecretService secrets;

    public JooqAiCatalogBootstrapRepository(DSLContext dslContext,
                                            RepositoryFactory repositoryFactory,
                                            ApplicationSecretService secrets) {
        super(dslContext, null, repositoryFactory);
        this.secrets = secrets;
    }

    @Override
    public void bootstrap(ScoreAiProperties properties) {
        dslContext().transaction(configuration -> bootstrap(
                org.jooq.impl.DSL.using(configuration), properties));
    }

    private void bootstrap(DSLContext tx, ScoreAiProperties properties) {
        if (tx.fetchCount(AI_PROVIDER) != 0 || tx.fetchCount(AI_MODEL) != 0
                || tx.fetchCount(AI_MODEL_CATALOG_CONFIG) != 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, AiProviderId> providerIds = insertProviders(tx, properties, now);
        Map<String, AiModelId> modelIds = insertModels(tx, properties, providerIds, now);
        insertDefaultModel(tx, properties, modelIds, now);
    }

    private Map<String, AiProviderId> insertProviders(
            DSLContext tx, ScoreAiProperties properties, LocalDateTime now) {
        Map<String, AiProviderId> providerIds = new LinkedHashMap<>();
        properties.getProviders().forEach((name, provider) -> {
            AppSecretId secretId = createSecret(tx, name, provider.getKey());
            AiProviderId id = new AiProviderId(tx.insertInto(AI_PROVIDER)
                    .set(AI_PROVIDER.PROVIDER_NAME, name)
                    .set(AI_PROVIDER.PROVIDER_TYPE, normalized(provider.getType(), "anthropic"))
                    .set(AI_PROVIDER.BASE_URL, blankToNull(provider.getBaseUrl()))
                    .set(AI_PROVIDER.MESSAGES_URL, blankToNull(provider.getMessagesUrl()))
                    .set(AI_PROVIDER.ANTHROPIC_VERSION, blankToNull(provider.getAnthropicVersion()))
                    .set(AI_PROVIDER.API_VERSION, blankToNull(provider.getApiVersion()))
                    .set(AI_PROVIDER.API_KEY_SECRET_ID, valueOf(secretId))
                    .set(AI_PROVIDER.ENABLED, (byte) 1)
                    .set(AI_PROVIDER.CREATED_AT, now)
                    .set(AI_PROVIDER.LAST_UPDATED_AT, now)
                    .returning(AI_PROVIDER.AI_PROVIDER_ID)
                    .fetchOne(AI_PROVIDER.AI_PROVIDER_ID).toBigInteger());
            providerIds.put(name, id);
        });
        return providerIds;
    }

    private AppSecretId createSecret(DSLContext tx, String providerName, String plaintext) {
        if (!StringUtils.hasText(plaintext)) return null;
        char[] value = plaintext.toCharArray();
        try {
            return new AppSecretId(secrets.create(
                    tx, "ai-provider/" + providerName + "/api-key", value, null).toBigInteger());
        } finally {
            ApplicationSecretService.clear(value);
        }
    }

    private Map<String, AiModelId> insertModels(
            DSLContext tx, ScoreAiProperties properties,
            Map<String, AiProviderId> providerIds, LocalDateTime now) {
        Map<String, AiModelId> modelIds = new LinkedHashMap<>();
        int order = 0;
        for (Map.Entry<String, ScoreAiProperties.Model> entry : properties.getModels().entrySet()) {
            String key = entry.getKey();
            ScoreAiProperties.Model model = entry.getValue();
            AiProviderId providerId = providerIds.get(model.getProvider());
            validateModel(key, model, providerId);
            var budget = model.getContextBudget();
            var capabilities = model.getModelCapabilities();
            AiModelId id = new AiModelId(tx.insertInto(AI_MODEL)
                    .set(AI_MODEL.PROVIDER_ID, valueOf(providerId))
                    .set(AI_MODEL.MODEL_KEY, key)
                    .set(AI_MODEL.PROVIDER_MODEL_NAME, normalized(model.getModel(), key))
                    .set(AI_MODEL.DISPLAY_NAME, normalized(model.getDisplayName(), key))
                    .set(AI_MODEL.DESCRIPTION, normalized(model.getDescription(), ""))
                    .set(AI_MODEL.ENABLED, (byte) 1)
                    .set(AI_MODEL.SORT_ORDER, UInteger.valueOf(order++))
                    .set(AI_MODEL.MAX_TOKENS, unsigned(model.getMaxTokens()))
                    .set(AI_MODEL.CONTEXT_WINDOW, ULong.valueOf(model.getContextWindow()))
                    .set(AI_MODEL.OUTPUT_RESERVE_TOKENS, unsigned(budget.getOutputReserveTokens()))
                    .set(AI_MODEL.AUTO_COMPACT_THRESHOLD_TOKENS,
                            unsigned(budget.getAutoCompactThresholdTokens()))
                    .set(AI_MODEL.EMERGENCY_HEADROOM_TOKENS,
                            ULong.valueOf(budget.getEmergencyHeadroomTokens() != null
                                    ? budget.getEmergencyHeadroomTokens() : 4096L))
                    .set(AI_MODEL.TOOL_OUTPUT_TOKEN_LIMIT,
                            ULong.valueOf(budget.getToolOutputTokenLimit() != null
                                    ? budget.getToolOutputTokenLimit() : 32000L))
                    .set(AI_MODEL.PROVIDER_COMPACTION_ENABLED,
                            (byte) (budget.isProviderCompactionEnabled() ? 1 : 0))
                    .set(AI_MODEL.TEMPERATURE, model.getTemperature() != null
                            ? java.math.BigDecimal.valueOf(model.getTemperature()) : null)
                    .set(AI_MODEL.THINKING_BUDGET_TOKENS, unsigned(model.getThinkingBudgetTokens()))
                    .set(AI_MODEL.ADAPTIVE_THINKING, (byte) (model.isAdaptiveThinking() ? 1 : 0))
                    .set(AI_MODEL.OUTPUT_EFFORT, blankToNull(model.getOutputEffort()))
                    .set(AI_MODEL.CACHE_STRATEGY, blankToNull(model.getCacheStrategy()))
                    .set(AI_MODEL.REASONING_MODEL_SUPPORTED,
                            nullableFlag(capabilities.getReasoningModel()))
                    .set(AI_MODEL.OUTPUT_EFFORT_SUPPORTED,
                            nullableFlag(capabilities.getOutputEffort()))
                    .set(AI_MODEL.VERBOSITY_SUPPORTED,
                            nullableFlag(capabilities.getVerbosity()))
                    .set(AI_MODEL.TEMPERATURE_SUPPORTED,
                            nullableFlag(capabilities.getTemperature()))
                    .set(AI_MODEL.THINKING_MODES_JSON,
                            AiChatJsonSerializer.getInstance().serialize(
                                    capabilities.getThinkingModes()))
                    .set(AI_MODEL.DEFAULT_THINKING,
                            blankToNull(capabilities.getDefaultThinking()))
                    .set(AI_MODEL.CREATED_AT, now)
                    .set(AI_MODEL.LAST_UPDATED_AT, now)
                    .returning(AI_MODEL.AI_MODEL_ID)
                    .fetchOne(AI_MODEL.AI_MODEL_ID).toBigInteger());
            modelIds.put(key, id);
            insertReasoningEfforts(tx, id, model);
        }
        return modelIds;
    }

    private static void validateModel(String key, ScoreAiProperties.Model model,
                                      AiProviderId providerId) {
        if (providerId == null) {
            throw new IllegalStateException("Unknown AI provider '" + model.getProvider()
                    + "' while bootstrapping model '" + key + "'.");
        }
        if (model.getContextWindow() == null || model.getContextWindow() <= 0) {
            throw new IllegalStateException("Model '" + key
                    + "' requires a positive context window for catalog bootstrap.");
        }
    }

    private void insertReasoningEfforts(DSLContext tx, AiModelId modelId,
                                        ScoreAiProperties.Model model) {
        List<ScoreAiProperties.ReasoningEffort> configured = model.getReasoningEfforts();
        if (configured == null || configured.isEmpty()) return;
        String defaultName = normalized(model.getReasoningEffort(),
                configured.getFirst().getName()).toLowerCase();
        int order = 0;
        for (ScoreAiProperties.ReasoningEffort effort : configured) {
            if (effort == null || !StringUtils.hasText(effort.getName())) continue;
            String name = effort.getName().strip().toLowerCase();
            if ("none".equals(name)) name = "disabled";
            tx.insertInto(AI_MODEL_REASONING_EFFORT)
                    .set(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID, valueOf(modelId))
                    .set(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT, name)
                    .set(AI_MODEL_REASONING_EFFORT.DISPLAY_NAME,
                            normalized(effort.getDisplayName(), effort.getName()))
                    .set(AI_MODEL_REASONING_EFFORT.DESCRIPTION,
                            normalized(effort.getDescription(), ""))
                    .set(AI_MODEL_REASONING_EFFORT.DEFAULT_EFFORT,
                            (byte) (name.equals(defaultName) ? 1 : 0))
                    .set(AI_MODEL_REASONING_EFFORT.SORT_ORDER, UInteger.valueOf(order++))
                    .execute();
        }
    }

    private void insertDefaultModel(DSLContext tx, ScoreAiProperties properties,
                                    Map<String, AiModelId> modelIds, LocalDateTime now) {
        AiModelId defaultModelId = modelIds.get(properties.getModelName());
        if (defaultModelId == null) {
            defaultModelId = modelIds.values().stream().findFirst().orElse(null);
        }
        if (defaultModelId == null) return;
        tx.insertInto(AI_MODEL_CATALOG_CONFIG)
                .set(AI_MODEL_CATALOG_CONFIG.AI_MODEL_CATALOG_CONFIG_ID,
                        UByte.valueOf(AiModelCatalogConfigId.GLOBAL.value().intValueExact()))
                .set(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID, valueOf(defaultModelId))
                .set(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_AT, now)
                .execute();
    }

    private static String normalized(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static UInteger unsigned(Integer value) {
        return value != null ? UInteger.valueOf(value) : null;
    }

    private static ULong unsigned(Long value) {
        return value != null ? ULong.valueOf(value) : null;
    }

    private static Byte nullableFlag(Boolean value) {
        return value != null ? (byte) (value ? 1 : 0) : null;
    }
}
