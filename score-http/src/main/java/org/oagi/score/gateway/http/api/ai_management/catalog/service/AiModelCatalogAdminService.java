package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.types.UByte;
import org.jooq.types.UInteger;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiModelRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.*;

@Service
public class AiModelCatalogAdminService {
    private final DSLContext dsl;
    private final AiAdminPolicyService authorization;
    private final ObjectMapper mapper;

    public AiModelCatalogAdminService(DSLContext dsl, AiAdminPolicyService authorization,
                                      ObjectMapper mapper) {
        this.dsl = dsl;
        this.authorization = authorization;
        this.mapper = mapper;
    }

    public List<AiModelCatalogView> list(ScoreUser actor) {
        authorization.requireAdministrator(actor);
        return dsl.selectFrom(AI_MODEL).orderBy(AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID)
                .fetch(row -> view(dsl, row));
    }

    public AiModelCatalogView get(ScoreUser actor, long id) {
        authorization.requireAdministrator(actor);
        var row = dsl.selectFrom(AI_MODEL)
                .where(AI_MODEL.AI_MODEL_ID.eq(ULong.valueOf(id))).fetchOne();
        if (row == null) throw new NotFoundException();
        return view(dsl, row);
    }

    public AiModelCatalogView create(ScoreUser actor, AiModelCatalogUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input, false, null);
        try {
            return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            AiModelProfile profile = requireModelProfile(tx, input);
            List<ReasoningEffort> efforts =
                    AiModelProfileSettingsValidator.validate(profile, input);
            ULong actorId = ULong.valueOf(actor.userId().value());
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            ULong id = tx.insertInto(AI_MODEL)
                    .set(configurationRecord(tx, profile, input))
                    .set(AI_MODEL.PROVIDER_ID, ULong.valueOf(input.providerId()))
                    .set(AI_MODEL.MODEL_KEY, input.modelKey().strip())
                    .set(AI_MODEL.ENABLED, flag(input.enabled()))
                    .set(AI_MODEL.SORT_ORDER, UInteger.valueOf(input.sortOrder()))
                    .set(AI_MODEL.CREATED_BY, actorId).set(AI_MODEL.LAST_UPDATED_BY, actorId)
                    .set(AI_MODEL.CREATED_AT, now).set(AI_MODEL.LAST_UPDATED_AT, now)
                    .returning(AI_MODEL.AI_MODEL_ID).fetchOne(AI_MODEL.AI_MODEL_ID);
            replaceEfforts(tx, id, efforts);
            updateDefault(tx, id, input.defaultModel()
                    || !tx.fetchExists(tx.selectOne().from(AI_MODEL_CATALOG_CONFIG)), actorId, now);
            AiModelCatalogView after = view(tx, tx.selectFrom(AI_MODEL)
                    .where(AI_MODEL.AI_MODEL_ID.eq(id)).fetchOne());
            audit(tx, id, actorId, "CREATE", null, after);
            return after;
            });
        } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
            throw conflict();
        }
    }

    public AiModelCatalogView update(ScoreUser actor, long modelId,
                                     AiModelCatalogUpdate input) {
        authorization.requireAdministrator(actor);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            ULong id = ULong.valueOf(modelId);
            var existing = tx.selectFrom(AI_MODEL)
                    .where(AI_MODEL.AI_MODEL_ID.eq(id)).forUpdate().fetchOne();
            if (existing == null) throw new NotFoundException();
            validate(input, true, existing.getModelKey());
            AiModelProfile profile = requireModelProfile(tx, input);
            List<ReasoningEffort> efforts =
                    AiModelProfileSettingsValidator.validate(profile, input);
            AiModelCatalogView before = view(tx, existing);
            boolean currentDefault = isDefault(tx, id);
            if (currentDefault && !input.defaultModel()) {
                throw new IllegalArgumentException(
                        "Choose another global default model before disabling this model.");
            }
            if (!input.enabled() && tx.fetchCount(AI_MODEL,
                    AI_MODEL.ENABLED.eq((byte) 1).and(AI_MODEL.AI_MODEL_ID.ne(id))) == 0) {
                throw new IllegalArgumentException("At least one active AI model is required.");
            }
            ULong actorId = ULong.valueOf(actor.userId().value());
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int changed = tx.update(AI_MODEL)
                    .set(configurationRecord(tx, profile, input))
                    .set(AI_MODEL.PROVIDER_ID, ULong.valueOf(input.providerId()))
                    .set(AI_MODEL.ENABLED, flag(input.enabled()))
                    .set(AI_MODEL.SORT_ORDER, UInteger.valueOf(input.sortOrder()))
                    .set(AI_MODEL.CATALOG_VERSION, AI_MODEL.CATALOG_VERSION.plus(1))
                    .set(AI_MODEL.LAST_UPDATED_BY, actorId).set(AI_MODEL.LAST_UPDATED_AT, now)
                    .where(AI_MODEL.AI_MODEL_ID.eq(id))
                    .and(AI_MODEL.CATALOG_VERSION.eq(ULong.valueOf(input.expectedVersion())))
                    .execute();
            if (changed != 1) throw conflict();
            replaceEfforts(tx, id, efforts);
            updateDefault(tx, id, input.defaultModel(), actorId, now);
            AiModelCatalogView after = view(tx, tx.selectFrom(AI_MODEL)
                    .where(AI_MODEL.AI_MODEL_ID.eq(id)).fetchOne());
            audit(tx, id, actorId, after.enabled() ? "UPDATE" : "DISABLE",
                    before, after);
            return after;
        });
    }

    AiModelRecord configurationRecord(DSLContext tx, AiModelProfile profile,
                                      AiModelCatalogUpdate input) {
        AiModelProfileSettingsResolver.ResolvedSettings settings =
                AiModelProfileSettingsResolver.resolve(profile, input);
        AiModelRecord record = tx.newRecord(AI_MODEL);
        record.setProviderModelName(profile.getProviderModelName());
        record.setDisplayName(profile.getDisplayName());
        record.setDescription(profile.getDescription());
        record.setMaxTokens(unsigned(settings.maxTokens()));
        record.setContextWindow(ULong.valueOf(input.contextWindow()));
        record.setOutputReserveTokens(unsigned(settings.outputReserveTokens()));
        record.setAutoCompactThresholdTokens(unsigned(settings.autoCompactThresholdTokens()));
        record.setEmergencyHeadroomTokens(ULong.valueOf(input.emergencyHeadroomTokens()));
        record.setToolOutputTokenLimit(ULong.valueOf(input.toolOutputTokenLimit()));
        record.setProviderCompactionEnabled(flag(input.providerCompactionEnabled()));
        record.setTemperature(settings.temperature() != null
                ? java.math.BigDecimal.valueOf(settings.temperature()) : null);
        record.setThinkingBudgetTokens(unsigned(settings.thinkingBudgetTokens()));
        record.setAdaptiveThinking(flag(input.adaptiveThinking()));
        record.setOutputEffort(normalized(input.outputEffort()));
        record.setCacheStrategy(normalized(input.cacheStrategy()));
        record.setReasoningModelSupported(flag(settings.reasoningOptionsEnabled()));
        record.setOutputEffortSupported(flag(settings.outputEffortEnabled()));
        record.setVerbositySupported(flag(settings.verbosityEnabled()));
        record.setTemperatureSupported(flag(settings.temperatureEnabled()));
        record.setThinkingModesJson(json(input.thinkingModes()));
        record.setDefaultThinking(normalized(input.defaultThinking()));
        return record;
    }

    private void validate(AiModelCatalogUpdate input, boolean update, String immutableKey) {
        if (input == null || !StringUtils.hasText(input.modelKey())) {
            throw new IllegalArgumentException("A model is required.");
        }
        if (update && input.expectedVersion() == null) {
            throw new IllegalArgumentException("Expected catalog version is required.");
        }
        if (immutableKey != null && !immutableKey.equals(input.modelKey().strip())) {
            throw new IllegalArgumentException("The model key is immutable.");
        }
        if (input.providerId() <= 0 || input.sortOrder() < 0) {
            throw new IllegalArgumentException("Provider and sort order are invalid.");
        }
        if (input.defaultModel() && !input.enabled()) {
            throw new IllegalArgumentException("The global default model must be enabled.");
        }
    }

    private AiModelProfile requireModelProfile(DSLContext tx, AiModelCatalogUpdate input) {
        var provider = tx.select(AI_PROVIDER.PROVIDER_TYPE, AI_PROVIDER.ENABLED).from(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(ULong.valueOf(input.providerId())))
                .forUpdate().fetchOne();
        if (provider == null || provider.value2() != 1) {
            throw new IllegalArgumentException("An enabled provider is required.");
        }
        String providerType = provider.value1();
        return AiModelProfileCatalog.find(providerType, input.modelKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The selected model is not supported by this provider."));
    }

    private void replaceEfforts(DSLContext tx, ULong id,
                                List<ReasoningEffort> efforts) {
        List<ReasoningEffort> requested = efforts != null
                ? efforts : List.of();
        java.util.Set<String> requestedNames = requested.stream()
                .map(e -> e.name().strip().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
        List<String> removed = tx.select(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT)
                .from(AI_MODEL_REASONING_EFFORT)
                .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(id))
                .and(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT.notIn(requestedNames))
                .fetch(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT);
        if (!removed.isEmpty() && tx.fetchExists(tx.selectOne()
                .from(AI_USER_MODEL_REASONING_ACCESS)
                .where(AI_USER_MODEL_REASONING_ACCESS.AI_MODEL_ID.eq(id))
                .and(AI_USER_MODEL_REASONING_ACCESS.REASONING_EFFORT.in(removed)))) {
            throw new IllegalArgumentException(
                    "Remove user policy references before deleting a reasoning effort.");
        }
        requested.forEach(e -> tx.insertInto(AI_MODEL_REASONING_EFFORT)
                .set(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID, id)
                .set(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT, e.name().strip().toLowerCase())
                .set(AI_MODEL_REASONING_EFFORT.DISPLAY_NAME, e.displayName().strip())
                .set(AI_MODEL_REASONING_EFFORT.DESCRIPTION, e.description().strip())
                .set(AI_MODEL_REASONING_EFFORT.DEFAULT_EFFORT, flag(e.defaultEffort()))
                .set(AI_MODEL_REASONING_EFFORT.SORT_ORDER, UInteger.valueOf(e.sortOrder()))
                .onDuplicateKeyUpdate()
                .set(AI_MODEL_REASONING_EFFORT.DISPLAY_NAME, e.displayName().strip())
                .set(AI_MODEL_REASONING_EFFORT.DESCRIPTION, e.description().strip())
                .set(AI_MODEL_REASONING_EFFORT.DEFAULT_EFFORT, flag(e.defaultEffort()))
                .set(AI_MODEL_REASONING_EFFORT.SORT_ORDER, UInteger.valueOf(e.sortOrder()))
                .execute());
        if (!removed.isEmpty()) {
            tx.deleteFrom(AI_MODEL_REASONING_EFFORT)
                    .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(id))
                    .and(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT.in(removed)).execute();
        }
    }

    private void updateDefault(DSLContext tx, ULong id, boolean makeDefault, ULong actor,
                               LocalDateTime now) {
        if (!makeDefault) return;
        tx.insertInto(AI_MODEL_CATALOG_CONFIG)
                .set(AI_MODEL_CATALOG_CONFIG.AI_MODEL_CATALOG_CONFIG_ID, UByte.valueOf(1))
                .set(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID, id)
                .set(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_BY, actor)
                .set(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_AT, now)
                .onDuplicateKeyUpdate()
                .set(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID, id)
                .set(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_BY, actor)
                .set(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_AT, now).execute();
    }

    private boolean isDefault(DSLContext tx, ULong id) {
        return tx.fetchExists(tx.selectOne().from(AI_MODEL_CATALOG_CONFIG)
                .where(AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID.eq(id)));
    }

    private AiModelCatalogView view(DSLContext tx, Record row) {
        ULong id = row.get(AI_MODEL.AI_MODEL_ID);
        String provider = tx.select(AI_PROVIDER.PROVIDER_NAME).from(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(row.get(AI_MODEL.PROVIDER_ID)))
                .fetchOne(AI_PROVIDER.PROVIDER_NAME);
        var efforts = tx.selectFrom(AI_MODEL_REASONING_EFFORT)
                .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(id))
                .orderBy(AI_MODEL_REASONING_EFFORT.SORT_ORDER)
                .fetch(e -> new AiModelCatalogView.ReasoningEffortView(e.getReasoningEffort(),
                        e.getDisplayName(), e.getDescription(), e.getDefaultEffort() == 1,
                        e.getSortOrder().intValue()));
        return new AiModelCatalogView(id.longValue(), row.get(AI_MODEL.PROVIDER_ID).longValue(),
                provider, row.get(AI_MODEL.MODEL_KEY), row.get(AI_MODEL.PROVIDER_MODEL_NAME),
                row.get(AI_MODEL.DISPLAY_NAME), row.get(AI_MODEL.DESCRIPTION),
                row.get(AI_MODEL.ENABLED) == 1, isDefault(tx, id),
                row.get(AI_MODEL.SORT_ORDER).intValue(),
                row.get(AI_MODEL.MAX_TOKENS) != null ? row.get(AI_MODEL.MAX_TOKENS).intValue() : null,
                row.get(AI_MODEL.CONTEXT_WINDOW).longValue(),
                longValue(row.get(AI_MODEL.OUTPUT_RESERVE_TOKENS)),
                longValue(row.get(AI_MODEL.AUTO_COMPACT_THRESHOLD_TOKENS)),
                row.get(AI_MODEL.EMERGENCY_HEADROOM_TOKENS).longValue(),
                row.get(AI_MODEL.TOOL_OUTPUT_TOKEN_LIMIT).longValue(),
                row.get(AI_MODEL.PROVIDER_COMPACTION_ENABLED) == 1,
                row.get(AI_MODEL.TEMPERATURE) != null
                        ? row.get(AI_MODEL.TEMPERATURE).doubleValue() : null,
                row.get(AI_MODEL.THINKING_BUDGET_TOKENS) != null
                        ? row.get(AI_MODEL.THINKING_BUDGET_TOKENS).intValue() : null,
                row.get(AI_MODEL.ADAPTIVE_THINKING) == 1,
                row.get(AI_MODEL.OUTPUT_EFFORT), row.get(AI_MODEL.CACHE_STRATEGY),
                nullableBoolean(row.get(AI_MODEL.REASONING_MODEL_SUPPORTED)),
                nullableBoolean(row.get(AI_MODEL.OUTPUT_EFFORT_SUPPORTED)),
                nullableBoolean(row.get(AI_MODEL.VERBOSITY_SUPPORTED)),
                nullableBoolean(row.get(AI_MODEL.TEMPERATURE_SUPPORTED)),
                parseJsonList(row.get(AI_MODEL.THINKING_MODES_JSON)),
                row.get(AI_MODEL.DEFAULT_THINKING),
                row.get(AI_MODEL.CATALOG_VERSION).longValue(), efforts);
    }

    private void audit(DSLContext tx, ULong id, ULong actor, String action, Object before,
                       Object after) {
        try {
            tx.insertInto(AI_CATALOG_AUDIT).set(AI_CATALOG_AUDIT.ENTITY_TYPE, "MODEL")
                    .set(AI_CATALOG_AUDIT.ENTITY_ID, id)
                    .set(AI_CATALOG_AUDIT.ACTOR_APP_USER_ID, actor)
                    .set(AI_CATALOG_AUDIT.ACTION, action)
                    .set(AI_CATALOG_AUDIT.BEFORE_JSON,
                            before != null ? mapper.writeValueAsString(before) : null)
                    .set(AI_CATALOG_AUDIT.AFTER_JSON,
                            after != null ? mapper.writeValueAsString(after) : null)
                    .set(AI_CATALOG_AUDIT.CREATED_AT, LocalDateTime.now(ZoneOffset.UTC)).execute();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize model catalog audit.", exception);
        }
    }

    private static byte flag(boolean value) { return (byte) (value ? 1 : 0); }
    private static Boolean nullableBoolean(Byte value) { return value != null ? value == 1 : null; }
    private static String normalized(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
    private String json(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(values.stream().map(String::strip).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize thinking modes.", exception);
        }
    }
    private List<String> parseJsonList(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try {
            return mapper.readValue(value, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid model thinking mode catalog JSON.", exception);
        }
    }
    private static UInteger unsigned(Integer value) { return value != null ? UInteger.valueOf(value) : null; }
    private static ULong unsigned(Long value) { return value != null ? ULong.valueOf(value) : null; }
    private static Long longValue(Number value) { return value != null ? value.longValue() : null; }
    private static AiPolicyViolationException conflict() {
        return new AiPolicyViolationException(AiPolicyErrorCode.AI_CATALOG_VERSION_CONFLICT,
                "The model catalog changed while it was being edited.");
    }
}
