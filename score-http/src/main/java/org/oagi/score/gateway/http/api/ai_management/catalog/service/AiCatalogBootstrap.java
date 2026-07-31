package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.jooq.DSLContext;
import org.jooq.types.UByte;
import org.jooq.types.UInteger;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
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

/** Imports the resolved legacy catalog once, and only when every catalog table is empty. */
@Component
@ConditionalOnProperty(prefix = "score.ai.catalog", name = "bootstrap-enabled", havingValue = "true")
public class AiCatalogBootstrap implements ApplicationRunner {

    private final DSLContext dsl;
    private final ScoreAiProperties properties;
    private final ApplicationSecretService secrets;

    public AiCatalogBootstrap(DSLContext dsl, ScoreAiProperties properties,
                              ApplicationSecretService secrets) {
        this.dsl = dsl;
        this.properties = properties;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapNow();
    }

    public void bootstrapNow() {
        dsl.transaction(configuration -> bootstrap(org.jooq.impl.DSL.using(configuration)));
    }

    private void bootstrap(DSLContext tx) {
        if (tx.fetchCount(AI_PROVIDER) != 0 || tx.fetchCount(AI_MODEL) != 0
                || tx.fetchCount(AI_MODEL_CATALOG_CONFIG) != 0) {
            return;
        }
        if (properties.getProviders().isEmpty() || properties.getModels().isEmpty()) return;
        if (!secrets.isEncryptionConfigured() && properties.getProviders().values().stream()
                .anyMatch(provider -> StringUtils.hasText(provider.getKey()))) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, ULong> providerIds = new LinkedHashMap<>();
        properties.getProviders().forEach((name, provider) -> {
            ULong secretId = null;
            if (StringUtils.hasText(provider.getKey())) {
                char[] value = provider.getKey().toCharArray();
                try {
                    secretId = secrets.create(tx, "ai-provider/" + name + "/api-key",
                            value, null);
                } finally {
                    ApplicationSecretService.clear(value);
                }
            }
            ULong id = tx.insertInto(AI_PROVIDER)
                    .set(AI_PROVIDER.PROVIDER_NAME, name)
                    .set(AI_PROVIDER.PROVIDER_TYPE, normalized(provider.getType(), "anthropic"))
                    .set(AI_PROVIDER.BASE_URL, blankToNull(provider.getBaseUrl()))
                    .set(AI_PROVIDER.MESSAGES_URL, blankToNull(provider.getMessagesUrl()))
                    .set(AI_PROVIDER.ANTHROPIC_VERSION, blankToNull(provider.getAnthropicVersion()))
                    .set(AI_PROVIDER.API_VERSION, blankToNull(provider.getApiVersion()))
                    .set(AI_PROVIDER.API_KEY_SECRET_ID, secretId)
                    .set(AI_PROVIDER.ENABLED, (byte) 1)
                    .set(AI_PROVIDER.CREATED_AT, now)
                    .set(AI_PROVIDER.LAST_UPDATED_AT, now)
                    .returning(AI_PROVIDER.AI_PROVIDER_ID)
                    .fetchOne(AI_PROVIDER.AI_PROVIDER_ID);
            providerIds.put(name, id);
        });

        Map<String, ULong> modelIds = new LinkedHashMap<>();
        int order = 0;
        for (Map.Entry<String, ScoreAiProperties.Model> entry : properties.getModels().entrySet()) {
            String key = entry.getKey();
            ScoreAiProperties.Model model = entry.getValue();
            ULong providerId = providerIds.get(model.getProvider());
            if (providerId == null) {
                throw new IllegalStateException("Unknown AI provider '" + model.getProvider()
                        + "' while bootstrapping model '" + key + "'.");
            }
            if (model.getContextWindow() == null || model.getContextWindow() <= 0) {
                throw new IllegalStateException("Model '" + key
                        + "' requires a positive context window for catalog bootstrap.");
            }
            var budget = model.getContextBudget();
            var capabilities = model.getModelCapabilities();
            ULong id = tx.insertInto(AI_MODEL)
                    .set(AI_MODEL.PROVIDER_ID, providerId)
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
                    .fetchOne(AI_MODEL.AI_MODEL_ID);
            modelIds.put(key, id);
            insertReasoningEfforts(tx, id, model);
        }

        ULong defaultModelId = modelIds.get(properties.getModelName());
        if (defaultModelId == null) defaultModelId = modelIds.values().stream().findFirst().orElse(null);
        if (defaultModelId != null) {
            tx.insertInto(AI_MODEL_CATALOG_CONFIG)
                    .set(AI_MODEL_CATALOG_CONFIG.AI_MODEL_CATALOG_CONFIG_ID, UByte.valueOf(1))
                    .set(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID, defaultModelId)
                    .set(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_AT, now)
                    .execute();
        }
    }

    private void insertReasoningEfforts(DSLContext tx, ULong modelId,
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
                    .set(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID, modelId)
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
