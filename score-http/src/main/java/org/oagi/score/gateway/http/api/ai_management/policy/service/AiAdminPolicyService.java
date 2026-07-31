package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
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
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_PERIOD;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_LEDGER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_QUOTA_ADJUSTMENT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_POLICY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_USER;

@Service
public class AiAdminPolicyService {

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
        return dsl.select(APP_USER.APP_USER_ID, APP_USER.LOGIN_ID, APP_USER.NAME,
                        APP_USER.ORGANIZATION, AI_USER_POLICY.AI_ENABLED,
                        AI_USER_POLICY.MULTI_AGENT_ENABLED,
                        AI_USER_POLICY.LAST_UPDATED_AT)
                .from(APP_USER)
                .leftJoin(AI_USER_POLICY)
                .on(AI_USER_POLICY.APP_USER_ID.eq(APP_USER.APP_USER_ID))
                .where(APP_USER.IS_ENABLED.eq((byte) 1))
                .orderBy(APP_USER.LOGIN_ID)
                .fetch(record -> {
                    boolean inherited = record.get(AI_USER_POLICY.AI_ENABLED) == null;
                    EffectiveAiPolicy effective = resolveFor(record.get(APP_USER.APP_USER_ID));
                    AiPolicyView.AiQuotaView quota = quotaView(effective);
                    return new AiPolicyUserSummary(
                            record.get(APP_USER.APP_USER_ID).toString(),
                            record.get(APP_USER.LOGIN_ID), record.get(APP_USER.NAME),
                            record.get(APP_USER.ORGANIZATION), inherited,
                            effective.aiEnabled(), effective.multiAgentEnabled(),
                            effective.availableModels().size(), quota.limitTokens(),
                            quota.consumedTokens(), quota.reservedTokens(),
                            quota.remainingTokens(),
                            requests.activeCountByUser(effective.userId()),
                            utc(record.get(AI_USER_POLICY.LAST_UPDATED_AT)));
                });
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
        Set<Long> allowedIds = new LinkedHashSet<>();
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
        Long defaultId = null;
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

        Map<Long, Set<String>> efforts = new LinkedHashMap<>();
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
                actor.userId(), update.expectedVersion(), update.reason());
        if (!saved.aiEnabled()) {
            requests.cancelByUser(targetUserId, "AI_DISABLED_BY_POLICY");
        }
        return view(resolveFor(ULong.valueOf(targetUserId.value())));
    }

    public void delete(ScoreUser actor, UserId targetUserId, long expectedVersion,
                       String reason) {
        requireAdministrator(actor);
        if (reason == null || reason.strip().length() < 10) {
            throw new IllegalArgumentException("A reset reason of at least 10 characters is required.");
        }
        commands.delete(targetUserId, actor.userId(), expectedVersion, reason.strip());
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
        if (input.reason() == null || input.reason().strip().length() < 10) {
            throw new IllegalArgumentException(
                    "A quota adjustment reason of at least 10 characters is required.");
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
                    .set(AI_TOKEN_QUOTA_ADJUSTMENT.REASON, input.reason().strip())
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
        if (model == null || model.id() <= 0) {
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
