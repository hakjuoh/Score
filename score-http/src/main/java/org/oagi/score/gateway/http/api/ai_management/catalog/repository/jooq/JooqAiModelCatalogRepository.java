package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.types.UInteger;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelOptions;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelProfileSettingsResolver;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiModelCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.AiAdminPage;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelProfileCatalog;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiModelRecord;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_REASONING_EFFORT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_MODEL_REASONING_ACCESS;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_USER;

public class JooqAiModelCatalogRepository extends JooqBaseRepository
        implements AiModelCatalogRepository {

    private static final org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AppUser
            UPDATER = APP_USER.as("updater");
    private static final Field<String> UPDATER_LOGIN_ID =
            UPDATER.LOGIN_ID.as("updater_login_id");

    private final ObjectMapper objectMapper;

    public JooqAiModelCatalogRepository(DSLContext dslContext,
                                        RepositoryFactory repositoryFactory,
                                        ObjectMapper objectMapper) {
        super(dslContext, null, repositoryFactory);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AiModelCatalogView> findAll() {
        return dslContext().select(AI_MODEL.fields()).select(UPDATER_LOGIN_ID)
                .from(AI_MODEL).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_MODEL.LAST_UPDATED_BY))
                .orderBy(AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID)
                .fetch(row -> view(dslContext(), row));
    }

    @Override
    public PageResponse<AiModelCatalogView> search(String model, String provider,
                                                   Boolean enabled, Boolean defaultModel,
                                                   String defaultEffort, String effort,
                                                   List<String> updaterLoginIdList,
                                                   Instant updatedAfter, Instant updatedBefore,
                                                   PageRequest pageRequest) {
        AiAdminPage.validate(pageRequest);
        Condition condition = DSL.trueCondition();
        if (StringUtils.hasText(model)) {
            String value = model.strip();
            condition = condition.and(AI_MODEL.DISPLAY_NAME.containsIgnoreCase(value)
                    .or(AI_MODEL.MODEL_KEY.containsIgnoreCase(value))
                    .or(AI_MODEL.PROVIDER_MODEL_NAME.containsIgnoreCase(value)));
        }
        if (StringUtils.hasText(provider)) {
            condition = condition.and(
                    AI_PROVIDER.PROVIDER_NAME.containsIgnoreCase(provider.strip()));
        }
        if (enabled != null) condition = condition.and(AI_MODEL.ENABLED.eq(flag(enabled)));
        if (defaultModel != null) {
            condition = condition.and(defaultModel
                    ? AI_MODEL.DEFAULT_MODEL.eq((byte) 1)
                    : AI_MODEL.DEFAULT_MODEL.isNull());
        }
        var effortFilter = AI_MODEL_REASONING_EFFORT.as("effort_filter");
        if (StringUtils.hasText(defaultEffort)) {
            condition = condition.andExists(DSL.selectOne().from(effortFilter)
                    .where(effortFilter.AI_MODEL_ID.eq(AI_MODEL.AI_MODEL_ID))
                    .and(effortFilter.DEFAULT_EFFORT.eq((byte) 1))
                    .and(effortFilter.DISPLAY_NAME.containsIgnoreCase(defaultEffort.strip())));
        }
        if (StringUtils.hasText(effort)) {
            condition = condition.andExists(DSL.selectOne().from(effortFilter)
                    .where(effortFilter.AI_MODEL_ID.eq(AI_MODEL.AI_MODEL_ID))
                    .and(effortFilter.DISPLAY_NAME.containsIgnoreCase(effort.strip())));
        }
        condition = condition.and(AiAdminPage.loginIdSelection(
                UPDATER.LOGIN_ID, updaterLoginIdList));
        if (updatedAfter != null) {
            condition = condition.and(AI_MODEL.LAST_UPDATE_TIMESTAMP.ge(
                    updatedAfter.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        if (updatedBefore != null) {
            condition = condition.and(AI_MODEL.LAST_UPDATE_TIMESTAMP.lt(
                    updatedBefore.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        var candidates = DSL.selectOne().from(AI_MODEL).join(AI_PROVIDER)
                .on(AI_PROVIDER.AI_PROVIDER_ID.eq(AI_MODEL.PROVIDER_ID))
                .leftJoin(UPDATER).on(UPDATER.APP_USER_ID.eq(AI_MODEL.LAST_UPDATED_BY))
                .where(condition);
        int total = dslContext().fetchCount(candidates);
        long offset = AiAdminPage.offset(pageRequest);
        if (offset >= total) {
            return new PageResponse<>(List.of(), pageRequest.pageIndex(),
                    pageRequest.pageSize(), total);
        }
        Field<String> defaultEffortField = DSL.field("(select min(e.display_name) from "
                + "ai_model_reasoning_effort e where e.ai_model_id = ai_model.ai_model_id "
                + "and e.default_effort = 1)", String.class);
        Field<String> effortsField = DSL.field("(select group_concat(e.display_name order by "
                + "e.sort_order separator ', ') from ai_model_reasoning_effort e "
                + "where e.ai_model_id = ai_model.ai_model_id)", String.class);
        List<SortField<?>> order = new ArrayList<>();
        pageRequest.sorts().forEach(sort -> {
            Field<?> field = switch (sort.field()) {
                case "model" -> AI_MODEL.DISPLAY_NAME;
                case "provider" -> AI_PROVIDER.PROVIDER_NAME;
                case "status" -> AI_MODEL.ENABLED;
                case "defaultEffort" -> defaultEffortField;
                case "efforts" -> effortsField;
                case "updater" -> UPDATER.LOGIN_ID;
                case "updatedOn" -> AI_MODEL.LAST_UPDATE_TIMESTAMP;
                default -> null;
            };
            if (field != null) order.add(sort.direction() == SortDirection.DESC
                    ? field.desc() : field.asc());
        });
        if (order.isEmpty()) {
            order.add(AI_MODEL.LAST_UPDATE_TIMESTAMP.desc());
        }
        order.add(AI_MODEL.AI_MODEL_ID.asc());
        List<AiModelCatalogView> page = dslContext().select(AI_MODEL.fields())
                .select(UPDATER_LOGIN_ID)
                .from(AI_MODEL).join(AI_PROVIDER)
                .on(AI_PROVIDER.AI_PROVIDER_ID.eq(AI_MODEL.PROVIDER_ID))
                .leftJoin(UPDATER).on(UPDATER.APP_USER_ID.eq(AI_MODEL.LAST_UPDATED_BY))
                .where(condition).orderBy(order).limit((int) offset, pageRequest.pageSize())
                .fetch(row -> view(dslContext(), row));
        return new PageResponse<>(page, pageRequest.pageIndex(), pageRequest.pageSize(), total);
    }

    @Override
    public Optional<AiModelCatalogView> findById(AiModelId modelId) {
        var row = dslContext().select(AI_MODEL.fields()).select(UPDATER_LOGIN_ID)
                .from(AI_MODEL).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_MODEL.LAST_UPDATED_BY))
                .where(AI_MODEL.AI_MODEL_ID.eq(valueOf(modelId))).fetchOne();
        return Optional.ofNullable(row).map(value -> view(dslContext(), value));
    }

    @Override
    public Optional<String> findEnabledProviderType(AiProviderId providerId) {
        return dslContext().select(AI_PROVIDER.PROVIDER_TYPE).from(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId)))
                .and(AI_PROVIDER.ENABLED.eq((byte) 1))
                .fetchOptional(AI_PROVIDER.PROVIDER_TYPE);
    }

    @Override
    public AiModelCatalogView create(UserId actorUserId, String providerType,
                                     AiModelCatalogUpdate input, AiModelProfile profile,
                                     List<ReasoningEffort> reasoningEfforts) {
        try {
            return dslContext().transactionResult(configuration -> {
                DSLContext tx = org.jooq.impl.DSL.using(configuration);
                requireEnabledProvider(tx, input.providerId(), providerType);
                LocalDateTime now = now();
                AiModelId id = new AiModelId(tx.insertInto(AI_MODEL)
                        .set(configurationRecord(tx, profile, input))
                        .set(AI_MODEL.PROVIDER_ID, valueOf(input.providerId()))
                        .set(AI_MODEL.MODEL_KEY, input.modelKey().strip())
                        .set(AI_MODEL.ENABLED, flag(input.enabled()))
                        .setNull(AI_MODEL.DEFAULT_MODEL)
                        .set(AI_MODEL.SORT_ORDER, UInteger.valueOf(input.sortOrder()))
                        .set(AI_MODEL.CREATED_BY, valueOf(actorUserId))
                        .set(AI_MODEL.LAST_UPDATED_BY, valueOf(actorUserId))
                        .set(AI_MODEL.CREATION_TIMESTAMP, now)
                        .set(AI_MODEL.LAST_UPDATE_TIMESTAMP, now)
                        .returning(AI_MODEL.AI_MODEL_ID).fetchOne(
                                AI_MODEL.AI_MODEL_ID).toBigInteger());
                replaceEfforts(tx, id, reasoningEfforts);
                boolean hasDefault = tx.fetchExists(tx.selectOne().from(AI_MODEL)
                        .where(AI_MODEL.DEFAULT_MODEL.eq((byte) 1)));
                updateDefault(tx, id, shouldMakeDefault(
                                input.defaultModel(), input.enabled(), hasDefault),
                        actorUserId, now);
                return view(tx, requireModel(tx, id));
            });
        } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
            throw new IllegalArgumentException("A model with this key already exists.", exception);
        }
    }

    @Override
    public AiModelCatalogView update(UserId actorUserId, AiModelId modelId, String providerType,
                                     AiModelCatalogUpdate input, AiModelProfile profile,
                                     List<ReasoningEffort> reasoningEfforts) {
        return dslContext().transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            var existing = tx.selectFrom(AI_MODEL)
                    .where(AI_MODEL.AI_MODEL_ID.eq(valueOf(modelId))).forUpdate().fetchOne();
            if (existing == null) throw new NotFoundException();
            if (!existing.getModelKey().equals(input.modelKey().strip())) {
                throw new IllegalArgumentException("The model key is immutable.");
            }
            requireEnabledProvider(tx, input.providerId(), providerType);
            if (isDefault(tx, modelId) && !input.defaultModel()) {
                throw new IllegalArgumentException(
                        "Choose another global default model before disabling this model.");
            }
            if (!input.enabled() && tx.fetchCount(AI_MODEL,
                    AI_MODEL.ENABLED.eq((byte) 1)
                            .and(AI_MODEL.AI_MODEL_ID.ne(valueOf(modelId)))) == 0) {
                throw new IllegalArgumentException("At least one active AI model is required.");
            }
            LocalDateTime now = now();
            tx.update(AI_MODEL)
                    .set(configurationRecord(tx, profile, input))
                    .set(AI_MODEL.PROVIDER_ID, valueOf(input.providerId()))
                    .set(AI_MODEL.ENABLED, flag(input.enabled()))
                    .set(AI_MODEL.SORT_ORDER, UInteger.valueOf(input.sortOrder()))
                    .set(AI_MODEL.LAST_UPDATED_BY, valueOf(actorUserId))
                    .set(AI_MODEL.LAST_UPDATE_TIMESTAMP, now)
                    .where(AI_MODEL.AI_MODEL_ID.eq(valueOf(modelId)))
                    .execute();
            replaceEfforts(tx, modelId, reasoningEfforts);
            boolean hasDefault = tx.fetchExists(tx.selectOne().from(AI_MODEL)
                    .where(AI_MODEL.DEFAULT_MODEL.eq((byte) 1)));
            updateDefault(tx, modelId, shouldMakeDefault(
                    input.defaultModel(), input.enabled(), hasDefault), actorUserId, now);
            return view(tx, requireModel(tx, modelId));
        });
    }

    @Override
    public ActiveCatalog findActiveCatalog() {
        List<ActiveModel> models = dslContext()
                .select(AI_MODEL.AI_MODEL_ID, AI_MODEL.MODEL_KEY)
                .from(AI_MODEL)
                .join(AI_PROVIDER).on(AI_PROVIDER.AI_PROVIDER_ID.eq(AI_MODEL.PROVIDER_ID))
                .where(AI_MODEL.ENABLED.eq((byte) 1))
                .and(AI_PROVIDER.ENABLED.eq((byte) 1))
                .orderBy(AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID)
                .fetch(row -> new ActiveModel(
                        new AiModelId(row.get(AI_MODEL.AI_MODEL_ID).toBigInteger()),
                        row.get(AI_MODEL.MODEL_KEY)));
        String defaultModelKey = dslContext().select(AI_MODEL.MODEL_KEY).from(AI_MODEL)
                .where(AI_MODEL.DEFAULT_MODEL.eq((byte) 1))
                .and(AI_MODEL.ENABLED.eq((byte) 1))
                .fetchOne(AI_MODEL.MODEL_KEY);
        return new ActiveCatalog(models, defaultModelKey);
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
        record.setModelOptionsJson(json(AiModelOptions.persisted(profile, input, settings)));
        return record;
    }

    private void requireEnabledProvider(DSLContext tx, AiProviderId providerId,
                                        String expectedProviderType) {
        var provider = tx.select(AI_PROVIDER.PROVIDER_TYPE, AI_PROVIDER.ENABLED)
                .from(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId)))
                .forUpdate().fetchOne();
        if (provider == null || provider.value2() != 1
                || !provider.value1().equals(expectedProviderType)) {
            throw new IllegalArgumentException("An enabled provider is required.");
        }
    }

    private AiModelRecord requireModel(DSLContext tx, AiModelId id) {
        AiModelRecord record = tx.selectFrom(AI_MODEL)
                .where(AI_MODEL.AI_MODEL_ID.eq(valueOf(id))).fetchOne();
        if (record == null) throw new NotFoundException();
        return record;
    }

    private void replaceEfforts(DSLContext tx, AiModelId id,
                                List<ReasoningEffort> efforts) {
        List<ReasoningEffort> requested = efforts != null ? efforts : List.of();
        java.util.Set<String> requestedNames = requested.stream()
                .map(effort -> effort.name().strip().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
        List<String> removed = tx.select(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT)
                .from(AI_MODEL_REASONING_EFFORT)
                .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(valueOf(id)))
                .and(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT.notIn(requestedNames))
                .fetch(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT);
        if (!removed.isEmpty() && tx.fetchExists(tx.selectOne()
                .from(AI_USER_MODEL_REASONING_ACCESS)
                .where(AI_USER_MODEL_REASONING_ACCESS.AI_MODEL_ID.eq(valueOf(id)))
                .and(AI_USER_MODEL_REASONING_ACCESS.REASONING_EFFORT.in(removed)))) {
            throw new IllegalArgumentException(
                    "Remove user policy references before deleting a reasoning effort.");
        }
        requested.forEach(effort -> tx.insertInto(AI_MODEL_REASONING_EFFORT)
                .set(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID, valueOf(id))
                .set(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT,
                        effort.name().strip().toLowerCase())
                .set(AI_MODEL_REASONING_EFFORT.DISPLAY_NAME, effort.displayName().strip())
                .set(AI_MODEL_REASONING_EFFORT.DESCRIPTION, effort.description().strip())
                .set(AI_MODEL_REASONING_EFFORT.DEFAULT_EFFORT, flag(effort.defaultEffort()))
                .set(AI_MODEL_REASONING_EFFORT.SORT_ORDER,
                        UInteger.valueOf(effort.sortOrder()))
                .onDuplicateKeyUpdate()
                .set(AI_MODEL_REASONING_EFFORT.DISPLAY_NAME, effort.displayName().strip())
                .set(AI_MODEL_REASONING_EFFORT.DESCRIPTION, effort.description().strip())
                .set(AI_MODEL_REASONING_EFFORT.DEFAULT_EFFORT, flag(effort.defaultEffort()))
                .set(AI_MODEL_REASONING_EFFORT.SORT_ORDER,
                        UInteger.valueOf(effort.sortOrder()))
                .execute());
        if (!removed.isEmpty()) {
            tx.deleteFrom(AI_MODEL_REASONING_EFFORT)
                    .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(valueOf(id)))
                    .and(AI_MODEL_REASONING_EFFORT.REASONING_EFFORT.in(removed)).execute();
        }
    }

    private void updateDefault(DSLContext tx, AiModelId id, boolean makeDefault,
                               UserId actorUserId, LocalDateTime now) {
        if (!makeDefault) return;
        tx.update(AI_MODEL)
                .setNull(AI_MODEL.DEFAULT_MODEL)
                .set(AI_MODEL.LAST_UPDATED_BY, valueOf(actorUserId))
                .set(AI_MODEL.LAST_UPDATE_TIMESTAMP, now)
                .where(AI_MODEL.DEFAULT_MODEL.eq((byte) 1))
                .and(AI_MODEL.AI_MODEL_ID.ne(valueOf(id))).execute();
        tx.update(AI_MODEL)
                .set(AI_MODEL.DEFAULT_MODEL, (byte) 1)
                .set(AI_MODEL.LAST_UPDATED_BY, valueOf(actorUserId))
                .set(AI_MODEL.LAST_UPDATE_TIMESTAMP, now)
                .where(AI_MODEL.AI_MODEL_ID.eq(valueOf(id))).execute();
    }

    static boolean shouldMakeDefault(boolean requestedDefault, boolean enabled,
                                     boolean hasDefault) {
        return requestedDefault || enabled && !hasDefault;
    }

    private boolean isDefault(DSLContext tx, AiModelId id) {
        return tx.fetchExists(tx.selectOne().from(AI_MODEL)
                .where(AI_MODEL.AI_MODEL_ID.eq(valueOf(id)))
                .and(AI_MODEL.DEFAULT_MODEL.eq((byte) 1)));
    }

    private AiModelCatalogView view(DSLContext tx, Record row) {
        AiModelId id = new AiModelId(row.get(AI_MODEL.AI_MODEL_ID).toBigInteger());
        Map<String, Object> options = parseJsonObject(row.get(AI_MODEL.MODEL_OPTIONS_JSON));
        var providerRecord = tx.select(AI_PROVIDER.PROVIDER_NAME, AI_PROVIDER.PROVIDER_TYPE)
                .from(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(row.get(AI_MODEL.PROVIDER_ID)))
                .fetchOne();
        String provider = providerRecord != null ? providerRecord.value1() : null;
        String providerType = providerRecord != null ? providerRecord.value2() : null;
        Map<String, Object> editableOptions = AiModelProfileCatalog
                .find(providerType, row.get(AI_MODEL.MODEL_KEY))
                .map(profile -> AiModelOptions.editable(profile, options)).orElse(Map.of());
        var efforts = tx.selectFrom(AI_MODEL_REASONING_EFFORT)
                .where(AI_MODEL_REASONING_EFFORT.AI_MODEL_ID.eq(valueOf(id)))
                .orderBy(AI_MODEL_REASONING_EFFORT.SORT_ORDER)
                .fetch(effort -> new AiModelCatalogView.ReasoningEffortView(
                        effort.getReasoningEffort(), effort.getDisplayName(),
                        effort.getDescription(), effort.getDefaultEffort() == 1,
                        effort.getSortOrder().intValue()));
        return new AiModelCatalogView(id,
                new AiProviderId(row.get(AI_MODEL.PROVIDER_ID).toBigInteger()),
                provider, row.get(AI_MODEL.MODEL_KEY), row.get(AI_MODEL.PROVIDER_MODEL_NAME),
                row.get(AI_MODEL.DISPLAY_NAME), row.get(AI_MODEL.DESCRIPTION),
                row.get(AI_MODEL.ENABLED) == 1,
                java.util.Objects.equals(row.get(AI_MODEL.DEFAULT_MODEL), (byte) 1),
                row.get(AI_MODEL.SORT_ORDER).intValue(),
                row.get(AI_MODEL.MAX_TOKENS) != null
                        ? row.get(AI_MODEL.MAX_TOKENS).intValue() : null,
                row.get(AI_MODEL.CONTEXT_WINDOW).longValue(),
                longValue(row.get(AI_MODEL.OUTPUT_RESERVE_TOKENS)),
                longValue(row.get(AI_MODEL.AUTO_COMPACT_THRESHOLD_TOKENS)),
                row.get(AI_MODEL.EMERGENCY_HEADROOM_TOKENS).longValue(),
                row.get(AI_MODEL.TOOL_OUTPUT_TOKEN_LIMIT).longValue(),
                booleanValue(options, "providerCompactionEnabled", false),
                doubleValue(options.get("temperature")),
                integerValue(options.get("thinkingBudgetTokens")),
                booleanValue(options, "adaptiveThinking", false),
                stringValue(options.get("outputEffort")),
                cacheStrategy(options.get("cacheStrategy")),
                nullableBoolean(options.get("reasoningModelSupported")),
                nullableBoolean(options.get("outputEffortSupported")),
                nullableBoolean(options.get("verbositySupported")),
                nullableBoolean(options.get("temperatureSupported")),
                stringList(options.get("thinkingModes")),
                stringValue(options.get("defaultThinking")), editableOptions, efforts,
                updaterLoginId(tx, row), utc(row.get(AI_MODEL.LAST_UPDATE_TIMESTAMP)));
    }

    private String updaterLoginId(DSLContext tx, Record row) {
        if (row.indexOf(UPDATER_LOGIN_ID) >= 0) return row.get(UPDATER_LOGIN_ID);
        ULong updaterId = row.get(AI_MODEL.LAST_UPDATED_BY);
        return updaterId != null ? tx.select(APP_USER.LOGIN_ID).from(APP_USER)
                .where(APP_USER.APP_USER_ID.eq(updaterId)).fetchOne(APP_USER.LOGIN_ID) : null;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize model options.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String value) {
        if (!StringUtils.hasText(value)) return Map.of();
        try {
            return objectMapper.readValue(value, LinkedHashMap.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid model options catalog JSON.", exception);
        }
    }

    private static boolean booleanValue(Map<String, Object> values, String key,
                                        boolean fallback) {
        return values.get(key) instanceof Boolean value ? value : fallback;
    }

    private static Boolean nullableBoolean(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private static Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
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

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static Instant utc(LocalDateTime value) {
        return value != null ? value.toInstant(ZoneOffset.UTC) : null;
    }

    private static byte flag(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private static UInteger unsigned(Integer value) {
        return value != null ? UInteger.valueOf(value) : null;
    }

    private static ULong unsigned(Long value) {
        return value != null ? ULong.valueOf(value) : null;
    }

    private static Long longValue(Number value) {
        return value != null ? value.longValue() : null;
    }

}
