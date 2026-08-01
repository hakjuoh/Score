package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.AiAdminPage;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelCatalogService;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiModelAccessMode;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUpdate;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUserSummary;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyView;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaWindow;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUserPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiAdminUsageView;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaAdjustmentRequest;
import org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.AiPolicyCommandRepository;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.AiPolicyQueryRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.oagi.score.gateway.http.common.model.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.ArrayList;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_PERIOD;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_LEDGER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_QUOTA_ADJUSTMENT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_POLICY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_USER;

@Service
public class AiAdminPolicyService {

    private static final org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AppUser
            UPDATER = APP_USER.as("updater");
    private static final Field<String> UPDATER_LOGIN_ID =
            UPDATER.LOGIN_ID.as("updater_login_id");

    private final DSLContext dsl;
    private final AiPolicyQueryRepository queries;
    private final AiPolicyCommandRepository commands;
    private final AiPolicyService policyService;
    private final AiModelCatalogService catalog;
    private final AiRequestRegistry requests;

    public AiAdminPolicyService(DSLContext dsl, AiPolicyQueryRepository queries,
                                AiPolicyCommandRepository commands,
                                AiPolicyService policyService,
                                AiModelCatalogService catalog,
                                AiRequestRegistry requests) {
        this.dsl = dsl;
        this.queries = queries;
        this.commands = commands;
        this.policyService = policyService;
        this.catalog = catalog;
        this.requests = requests;
    }

    public List<AiPolicyUserSummary> users(ScoreUser actor) {
        requireAdministrator(actor);
        return allUserSummaries();
    }

    private List<AiPolicyUserSummary> allUserSummaries() {
        return candidateUserSummaries(null, null, null, null, null, null);
    }

    private List<AiPolicyUserSummary> candidateUserSummaries(
            String loginId, String name, String organization,
            List<String> updaterLoginIdList, Instant updatedAfter, Instant updatedBefore) {
        return dsl.select(APP_USER.APP_USER_ID, APP_USER.LOGIN_ID, APP_USER.NAME,
                        APP_USER.ORGANIZATION, AI_USER_POLICY.AI_ENABLED,
                        AI_USER_POLICY.MULTI_AGENT_ENABLED,
                        AI_USER_POLICY.LAST_UPDATED_AT, UPDATER_LOGIN_ID)
                .from(APP_USER)
                .leftJoin(AI_USER_POLICY)
                .on(AI_USER_POLICY.APP_USER_ID.eq(APP_USER.APP_USER_ID))
                .leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_USER_POLICY.LAST_UPDATED_BY))
                .where(basicUserCondition(loginId, name, organization,
                        updaterLoginIdList, updatedAfter, updatedBefore))
                .orderBy(APP_USER.LOGIN_ID)
                .fetch(this::summary);
    }

    public PageResponse<AiPolicyUserSummary> searchUsers(
            ScoreUser actor, String loginId, String name, String organization,
            Boolean enabled, Integer modelCount, Boolean multiAgentEnabled,
            String quota, Integer activeRequests, List<String> updaterLoginIdList,
            Instant updatedAfter, Instant updatedBefore, PageRequest pageRequest) {
        requireAdministrator(actor);
        if (canPageInDatabase(enabled, modelCount, multiAgentEnabled, quota,
                activeRequests, pageRequest)) {
            return searchDatabasePage(loginId, name, organization, updaterLoginIdList,
                    updatedAfter, updatedBefore, pageRequest);
        }
        return AiAdminPage.of(candidateUserSummaries(loginId, name, organization,
                        updaterLoginIdList, updatedAfter, updatedBefore).stream()
                        .filter(user -> AiAdminPage.contains(user.loginId(), loginId))
                        .filter(user -> AiAdminPage.contains(user.name(), name))
                        .filter(user -> AiAdminPage.contains(user.organization(), organization))
                        .filter(user -> enabled == null || user.enabled() == enabled)
                        .filter(user -> modelCount == null
                                || user.allowedModelCount() == modelCount)
                        .filter(user -> multiAgentEnabled == null
                                || user.multiAgentEnabled() == multiAgentEnabled)
                        .filter(user -> matchesQuota(user, quota))
                        .filter(user -> activeRequests == null
                                || user.activeRequests() == activeRequests)
                        .filter(user -> updatedAfter == null || user.lastUpdatedAt() != null
                                && !user.lastUpdatedAt().isBefore(updatedAfter))
                        .filter(user -> updatedBefore == null || user.lastUpdatedAt() != null
                                && user.lastUpdatedAt().isBefore(updatedBefore)),
                pageRequest, AiAdminPolicyService::calculatedComparator,
                defaultCalculatedOrder());
    }

    static Comparator<AiPolicyUserSummary> calculatedComparator(Sort sort) {
        return switch (sort.field()) {
            case "loginId" -> Comparator.comparing(AiPolicyUserSummary::loginId,
                    String.CASE_INSENSITIVE_ORDER);
            case "name" -> Comparator.comparing(AiPolicyUserSummary::name,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "organization" -> Comparator.comparing(AiPolicyUserSummary::organization,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "access" -> Comparator.comparing(AiPolicyUserSummary::enabled);
            case "models" -> Comparator.comparingInt(AiPolicyUserSummary::allowedModelCount);
            case "multiAgent" -> Comparator.comparing(AiPolicyUserSummary::multiAgentEnabled);
            case "quota" -> Comparator.comparingLong(AiAdminPolicyService::quotaUsed);
            case "active" -> Comparator.comparingInt(AiPolicyUserSummary::activeRequests);
            case "updater" -> Comparator.comparing(AiPolicyUserSummary::updaterLoginId,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "updatedOn" -> Comparator.comparing(AiPolicyUserSummary::lastUpdatedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            default -> null;
        };
    }

    static Comparator<AiPolicyUserSummary> defaultCalculatedOrder() {
        return Comparator.comparing(AiPolicyUserSummary::lastUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AiPolicyUserSummary::loginId,
                        String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * Fields backed directly by APP_USER/AI_USER_POLICY can be counted and paged in SQL.
     * Effective model, quota, and active-request fields require policy resolution first and
     * therefore use the calculated-result path above to preserve exact filter semantics.
     */
    private boolean canPageInDatabase(Boolean enabled, Integer modelCount,
                                      Boolean multiAgentEnabled, String quota,
                                      Integer activeRequests, PageRequest request) {
        if (enabled != null || modelCount != null || multiAgentEnabled != null
                || activeRequests != null
                || AiAdminPage.hasText(quota) && !"ALL".equalsIgnoreCase(quota)) return false;
        return request.sorts().stream().allMatch(sort -> Set.of(
                "loginId", "name", "organization", "updater", "updatedOn")
                .contains(sort.field()));
    }

    private PageResponse<AiPolicyUserSummary> searchDatabasePage(
            String loginId, String name, String organization,
            List<String> updaterLoginIdList, Instant updatedAfter,
            Instant updatedBefore, PageRequest request) {
        AiAdminPage.validate(request);
        Condition condition = basicUserCondition(loginId, name, organization,
                updaterLoginIdList, updatedAfter, updatedBefore);
        var candidates = DSL.selectOne().from(APP_USER).leftJoin(AI_USER_POLICY)
                .on(AI_USER_POLICY.APP_USER_ID.eq(APP_USER.APP_USER_ID))
                .leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_USER_POLICY.LAST_UPDATED_BY)).where(condition);
        int total = dsl.fetchCount(candidates);
        long offset = AiAdminPage.offset(request);
        if (offset >= total) {
            return new PageResponse<>(List.of(), request.pageIndex(), request.pageSize(), total);
        }
        List<SortField<?>> order = new ArrayList<>();
        request.sorts().forEach(sort -> {
            Field<?> field = switch (sort.field()) {
                case "loginId" -> APP_USER.LOGIN_ID;
                case "name" -> APP_USER.NAME;
                case "organization" -> APP_USER.ORGANIZATION;
                case "updater" -> UPDATER.LOGIN_ID;
                case "updatedOn" -> AI_USER_POLICY.LAST_UPDATED_AT;
                default -> null;
            };
            if (field != null) order.add(sort.direction() == SortDirection.DESC
                    ? field.desc() : field.asc());
        });
        if (order.isEmpty()) order.add(AI_USER_POLICY.LAST_UPDATED_AT.desc());
        order.add(APP_USER.APP_USER_ID.asc());
        List<AiPolicyUserSummary> page = dsl.select(APP_USER.APP_USER_ID, APP_USER.LOGIN_ID,
                        APP_USER.NAME, APP_USER.ORGANIZATION, AI_USER_POLICY.AI_ENABLED,
                        AI_USER_POLICY.MULTI_AGENT_ENABLED, AI_USER_POLICY.LAST_UPDATED_AT,
                        UPDATER_LOGIN_ID)
                .from(APP_USER).leftJoin(AI_USER_POLICY)
                .on(AI_USER_POLICY.APP_USER_ID.eq(APP_USER.APP_USER_ID))
                .leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_USER_POLICY.LAST_UPDATED_BY))
                .where(condition).orderBy(order).limit((int) offset, request.pageSize())
                .fetch(this::summary);
        return new PageResponse<>(page, request.pageIndex(), request.pageSize(), total);
    }

    private Condition basicUserCondition(
            String loginId, String name, String organization,
            List<String> updaterLoginIdList, Instant updatedAfter, Instant updatedBefore) {
        Condition condition = APP_USER.IS_ENABLED.eq((byte) 1);
        if (AiAdminPage.hasText(loginId)) {
            condition = condition.and(APP_USER.LOGIN_ID.containsIgnoreCase(loginId.strip()));
        }
        if (AiAdminPage.hasText(name)) {
            condition = condition.and(APP_USER.NAME.containsIgnoreCase(name.strip()));
        }
        if (AiAdminPage.hasText(organization)) {
            condition = condition.and(
                    APP_USER.ORGANIZATION.containsIgnoreCase(organization.strip()));
        }
        condition = condition.and(AiAdminPage.loginIdSelection(
                UPDATER.LOGIN_ID, updaterLoginIdList));
        if (updatedAfter != null) {
            condition = condition.and(AI_USER_POLICY.LAST_UPDATED_AT.ge(
                    updatedAfter.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        if (updatedBefore != null) {
            condition = condition.and(AI_USER_POLICY.LAST_UPDATED_AT.lt(
                    updatedBefore.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        return condition;
    }

    private AiPolicyUserSummary summary(Record record) {
        boolean inherited = record.get(AI_USER_POLICY.AI_ENABLED) == null;
        EffectiveAiPolicy effective = resolveFor(record.get(APP_USER.APP_USER_ID));
        AiPolicyView.AiQuotaView quota = quotaView(effective);
        return new AiPolicyUserSummary(record.get(APP_USER.APP_USER_ID).toString(),
                record.get(APP_USER.LOGIN_ID), record.get(APP_USER.NAME),
                record.get(APP_USER.ORGANIZATION), inherited, effective.aiEnabled(),
                effective.multiAgentEnabled(), effective.availableModels().size(),
                quota.limitTokens(), quota.consumedTokens(), quota.reservedTokens(),
                quota.remainingTokens(), requests.activeCountByUser(effective.userId()),
                record.get(UPDATER_LOGIN_ID), utc(record.get(AI_USER_POLICY.LAST_UPDATED_AT)));
    }

    private boolean matchesQuota(AiPolicyUserSummary user, String quota) {
        if (!AiAdminPage.hasText(quota) || "ALL".equalsIgnoreCase(quota)) return true;
        if ("UNLIMITED".equalsIgnoreCase(quota)) return user.quotaLimitTokens() == null;
        if (user.quotaLimitTokens() == null) return false;
        long used = quotaUsed(user);
        if ("EXHAUSTED".equalsIgnoreCase(quota)) return used >= user.quotaLimitTokens();
        if ("NEAR".equalsIgnoreCase(quota)) {
            return used >= user.quotaLimitTokens() * 0.8 && used < user.quotaLimitTokens();
        }
        if ("AVAILABLE".equalsIgnoreCase(quota)) return used < user.quotaLimitTokens() * 0.8;
        throw new IllegalArgumentException("Unsupported quota filter: " + quota);
    }

    private static long quotaUsed(AiPolicyUserSummary user) {
        return user.quotaConsumedTokens() + user.quotaReservedTokens();
    }

    public AiPolicyView get(ScoreUser actor, UserId targetUserId) {
        requireAdministrator(actor);
        requireUser(targetUserId);
        return view(resolveFor(ULong.valueOf(targetUserId.value())));
    }

    public AiPolicyView self(ScoreUser requester) {
        return view(policyService.resolve(requester));
    }

    public AiPolicyView update(ScoreUser actor, UserId targetUserId, AiPolicyUpdate update) {
        requireAdministrator(actor);
        requireUser(targetUserId);
        validate(update);

        Map<String, AiCatalogModel> models = catalog.activeModels().stream().collect(
                java.util.stream.Collectors.toMap(model -> model.descriptor().name(),
                        model -> model, (first, ignored) -> first, LinkedHashMap::new));
        Set<AiModelId> allowedIds = new LinkedHashSet<>();
        if (update.modelAccessMode() == AiModelAccessMode.ALLOW_LIST) {
            update.allowedModelKeys().forEach(key -> allowedIds.add(requireModel(models, key).id()));
            if (update.aiEnabled() && allowedIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one model is required when AI is enabled in allow-list mode.");
            }
        }
        List<AiCatalogModel> effectiveModels = update.modelAccessMode() == AiModelAccessMode.ALL
                ? List.copyOf(models.values()) : models.values().stream()
                .filter(model -> allowedIds.contains(model.id())).toList();
        AiModelId defaultId = null;
        if (update.defaultModelKey() != null && !update.defaultModelKey().isBlank()) {
            AiCatalogModel defaultModel = requireModel(models, update.defaultModelKey());
            if (!effectiveModels.contains(defaultModel)) {
                throw new IllegalArgumentException("The default model must be an allowed model.");
            }
            defaultId = defaultModel.id();
        }
        if (update.aiEnabled() && effectiveModels.isEmpty()) {
            throw new IllegalArgumentException("At least one active AI model is required.");
        }

        Map<AiModelId, Set<String>> efforts = new LinkedHashMap<>();
        update.allowedReasoningEfforts().forEach((modelKey, requestedEfforts) -> {
            AiCatalogModel model = requireModel(models, modelKey);
            if (!effectiveModels.contains(model)) {
                throw new IllegalArgumentException(
                        "Reasoning restrictions may only reference an allowed model: " + modelKey);
            }
            Set<String> supported = model.descriptor().reasoningEfforts().stream()
                    .map(effort -> effort.name().toLowerCase()).collect(java.util.stream.Collectors.toSet());
            Set<String> normalized = requestedEfforts.stream().map(String::strip)
                    .map(String::toLowerCase).collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new));
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Select at least one reasoning effort for model '" + modelKey + "'.");
            }
            if (!supported.containsAll(normalized)) {
                throw new IllegalArgumentException(
                        "The reasoning effort list contains a value not supported by model '"
                                + modelKey + "'.");
            }
            if (!normalized.isEmpty()) efforts.put(model.id(), normalized);
        });

        AiUserPolicy saved = commands.save(new AiUserPolicy(targetUserId,
                        update.aiEnabled(), update.modelAccessMode(), defaultId,
                        update.multiAgentEnabled(), update.maxAgentsPerRequest(),
                        update.maxActiveRequests(), update.maxOutputTokensPerCall(),
                        update.maxTotalTokensPerRequest(), update.quotaPeriod(),
                        update.quotaTokens(), update.expectedVersion() != null
                        ? update.expectedVersion() : 0L, allowedIds, efforts),
                actor.userId(), update.expectedVersion());
        if (!saved.aiEnabled()) {
            requests.cancelByUser(targetUserId, "AI_DISABLED_BY_POLICY");
        }
        return view(resolveFor(ULong.valueOf(targetUserId.value())));
    }

    public void delete(ScoreUser actor, UserId targetUserId, long expectedVersion) {
        requireAdministrator(actor);
        commands.delete(targetUserId, actor.userId(), expectedVersion);
    }

    public AiAdminUsageView usage(ScoreUser actor, UserId targetUserId) {
        requireAdministrator(actor);
        requireUser(targetUserId);
        EffectiveAiPolicy policy = resolveFor(ULong.valueOf(targetUserId.value()));
        var calls = dsl.select(AI_TOKEN_USAGE_LEDGER.CALL_ID, AI_MODEL.MODEL_KEY,
                        AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND, AI_TOKEN_USAGE_LEDGER.AGENT_ID,
                        AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS,
                        AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS,
                        AI_TOKEN_USAGE_LEDGER.USAGE_COMPLETE, AI_TOKEN_USAGE_LEDGER.STATUS,
                        AI_TOKEN_USAGE_LEDGER.FAILURE_TYPE, AI_TOKEN_USAGE_LEDGER.RESERVED_AT,
                        AI_TOKEN_USAGE_LEDGER.SETTLED_AT)
                .from(AI_TOKEN_USAGE_LEDGER)
                .join(AI_MODEL).on(AI_MODEL.AI_MODEL_ID.eq(AI_TOKEN_USAGE_LEDGER.AI_MODEL_ID))
                .where(AI_TOKEN_USAGE_LEDGER.APP_USER_ID.eq(
                        ULong.valueOf(targetUserId.value())))
                .orderBy(AI_TOKEN_USAGE_LEDGER.RESERVED_AT.desc()).limit(100)
                .fetch(row -> new AiAdminUsageView.LedgerEntry(
                        row.get(AI_TOKEN_USAGE_LEDGER.CALL_ID), row.get(AI_MODEL.MODEL_KEY),
                        row.get(AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND),
                        row.get(AI_TOKEN_USAGE_LEDGER.AGENT_ID),
                        row.get(AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS).longValue(),
                        row.get(AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS).longValue(),
                        row.get(AI_TOKEN_USAGE_LEDGER.USAGE_COMPLETE) == 1,
                        row.get(AI_TOKEN_USAGE_LEDGER.STATUS),
                        row.get(AI_TOKEN_USAGE_LEDGER.FAILURE_TYPE),
                        utc(row.get(AI_TOKEN_USAGE_LEDGER.RESERVED_AT)),
                        utc(row.get(AI_TOKEN_USAGE_LEDGER.SETTLED_AT))));
        return new AiAdminUsageView(quotaView(policy), requests.activeCountByUser(targetUserId), calls);
    }

    public AiAdminUsageView adjustQuota(ScoreUser actor, UserId targetUserId,
                                        AiQuotaAdjustmentRequest input) {
        requireAdministrator(actor);
        requireUser(targetUserId);
        if (input == null || input.deltaTokens() == 0) {
            throw new IllegalArgumentException("A non-zero quota adjustment is required.");
        }
        EffectiveAiPolicy policy = resolveFor(ULong.valueOf(targetUserId.value()));
        AiQuotaWindow window = policy.currentQuotaWindow(Instant.now()).orElseThrow(() ->
                new IllegalArgumentException("The user does not have a period quota."));
        dsl.transaction(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            ULong target = ULong.valueOf(targetUserId.value());
            var start = window.start().atZone(ZoneOffset.UTC).toLocalDateTime();
            var end = window.end().atZone(ZoneOffset.UTC).toLocalDateTime();
            var now = java.time.LocalDateTime.now(ZoneOffset.UTC);
            tx.insertInto(AI_TOKEN_USAGE_PERIOD)
                    .set(AI_TOKEN_USAGE_PERIOD.APP_USER_ID, target)
                    .set(AI_TOKEN_USAGE_PERIOD.PERIOD_START, start)
                    .set(AI_TOKEN_USAGE_PERIOD.PERIOD_END, end)
                    .set(AI_TOKEN_USAGE_PERIOD.UPDATED_AT, now)
                    .onDuplicateKeyIgnore().execute();
            var period = tx.selectFrom(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(target))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(start))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_END.eq(end)).forUpdate().fetchOne();
            long consumed = period.getConsumedTokens().longValue();
            long adjusted = Math.addExact(consumed, input.deltaTokens());
            if (adjusted < 0) {
                throw new IllegalArgumentException(
                        "The quota adjustment cannot make consumed usage negative.");
            }
            period.setConsumedTokens(ULong.valueOf(adjusted));
            period.setUpdatedAt(now);
            period.update();
            tx.insertInto(AI_TOKEN_QUOTA_ADJUSTMENT)
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.TARGET_APP_USER_ID, target)
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.ACTOR_APP_USER_ID,
                            ULong.valueOf(actor.userId().value()))
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.PERIOD_START, start)
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.DELTA_TOKENS, input.deltaTokens())
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.REASON, "Manual quota adjustment")
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.CREATED_AT, now).execute();
        });
        return usage(actor, targetUserId);
    }

    public int cancelActiveRequests(ScoreUser actor, UserId targetUserId) {
        requireAdministrator(actor);
        requireUser(targetUserId);
        return requests.cancelByUser(targetUserId, "ADMIN_CANCELLED_ACTIVE_REQUESTS");
    }

    private EffectiveAiPolicy resolveFor(ULong userId) {
        ScoreUser synthetic = new ScoreUser(new UserId(userId.toBigInteger()), null,
                null, null, false, List.of(ScoreRole.END_USER));
        return policyService.resolve(synthetic);
    }

    private AiPolicyView view(EffectiveAiPolicy effective) {
        Map<String, List<String>> efforts = new LinkedHashMap<>();
        effective.availableModels().forEach(model -> {
            Set<String> restricted = effective.allowedReasoningEfforts().get(model.id());
            if (restricted != null) efforts.put(model.descriptor().name(), List.copyOf(restricted));
        });
        return new AiPolicyView(effective.userId().toString(), effective.inherited(),
                effective.policyVersion(), effective.aiEnabled(),
                queries.find(effective.userId()).map(policy -> policy.modelAccessMode().name())
                        .orElse(AiModelAccessMode.ALL.name()),
                effective.defaultModelKey(), effective.availableModels().stream()
                .map(model -> model.descriptor().name()).toList(), efforts,
                effective.multiAgentEnabled(), effective.maxAgentsPerRequest(),
                effective.maxActiveRequests(),
                requests.activeCountByUser(effective.userId()) > 0,
                effective.maxOutputTokensPerCall(),
                effective.maxTotalTokensPerRequest(), quotaView(effective));
    }

    private AiPolicyView.AiQuotaView quotaView(EffectiveAiPolicy policy) {
        AiQuotaWindow window = policy.currentQuotaWindow(Instant.now()).orElse(null);
        if (window == null) return new AiPolicyView.AiQuotaView(
                null, null, 0L, 0L, null, null, null);
        var row = dsl.select(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS,
                        AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS)
                .from(AI_TOKEN_USAGE_PERIOD)
                .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(
                        ULong.valueOf(policy.userId().value())))
                .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(
                        window.start().atZone(ZoneOffset.UTC).toLocalDateTime()))
                .and(AI_TOKEN_USAGE_PERIOD.PERIOD_END.eq(
                        window.end().atZone(ZoneOffset.UTC).toLocalDateTime()))
                .fetchOne();
        long consumed = row != null
                ? row.get(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS).longValue() : 0L;
        long reserved = row != null
                ? row.get(AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS).longValue() : 0L;
        return new AiPolicyView.AiQuotaView(policy.quotaPeriod().name(), window.limitTokens(),
                consumed, reserved, Math.max(0L, window.limitTokens() - consumed - reserved),
                window.start(), window.end());
    }

    private static Instant utc(java.time.LocalDateTime value) {
        return value != null ? value.toInstant(ZoneOffset.UTC) : null;
    }

    private void validate(AiPolicyUpdate update) {
        if (update == null) throw new IllegalArgumentException("AI policy payload is required.");
        if (update.maxAgentsPerRequest() < 1 || update.maxAgentsPerRequest() > 4) {
            throw new IllegalArgumentException("Maximum agents must be between 1 and 4.");
        }
        if (update.maxActiveRequests() < 1 || update.maxActiveRequests() > 32) {
            throw new IllegalArgumentException("Maximum active requests must be between 1 and 32.");
        }
        positiveOrNull(update.maxOutputTokensPerCall(), "Maximum output tokens per call");
        positiveOrNull(update.maxTotalTokensPerRequest(), "Maximum total tokens per request");
        positiveOrNull(update.quotaTokens(), "Quota tokens");
        if ((update.quotaPeriod() == null) != (update.quotaTokens() == null)) {
            throw new IllegalArgumentException("Quota period and quota tokens must be set together.");
        }
    }

    private void positiveOrNull(Long value, String label) {
        if (value != null && value <= 0) throw new IllegalArgumentException(label + " must be positive.");
    }

    private AiCatalogModel requireModel(Map<String, AiCatalogModel> models, String key) {
        AiCatalogModel model = key != null ? models.get(key.strip()) : null;
        if (model == null || model.id() == null || model.id().value() == null
                || model.id().value().signum() <= 0) {
            throw new IllegalArgumentException("Unknown active AI model: " + key);
        }
        return model;
    }

    private void requireUser(UserId userId) {
        if (userId == null || !dsl.fetchExists(dsl.selectOne().from(APP_USER)
                .where(APP_USER.APP_USER_ID.eq(ULong.valueOf(userId.value()))))) {
            throw new org.oagi.score.gateway.http.common.model.NotFoundException();
        }
    }

    public void requireAdministrator(ScoreUser actor) {
        if (actor == null || !actor.isAdministrator()) {
            throw new AccessDeniedException("Administrator access is required.");
        }
    }
}
