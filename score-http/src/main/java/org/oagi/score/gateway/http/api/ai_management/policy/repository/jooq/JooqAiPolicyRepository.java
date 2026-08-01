package org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.types.UByte;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyVersionConflictException;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiModelAccessMode;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaPeriod;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUserPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.AiPolicyCommandRepository;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.AiPolicyQueryRepository;
import org.oagi.score.gateway.http.common.model.Id;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_MODEL_ACCESS;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_MODEL_REASONING_ACCESS;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_POLICY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_POLICY_AUDIT;

@Repository
public class JooqAiPolicyRepository implements AiPolicyQueryRepository, AiPolicyCommandRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqAiPolicyRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AiUserPolicy> find(UserId userId) {
        return find(dsl, userId);
    }

    private Optional<AiUserPolicy> find(DSLContext tx, UserId userId) {
        ULong id = unsigned(userId);
        var row = tx.selectFrom(AI_USER_POLICY)
                .where(AI_USER_POLICY.APP_USER_ID.eq(id)).fetchOne();
        if (row == null) return Optional.empty();

        Set<AiModelId> allowedModels = tx.select(AI_USER_MODEL_ACCESS.AI_MODEL_ID)
                .from(AI_USER_MODEL_ACCESS)
                .where(AI_USER_MODEL_ACCESS.APP_USER_ID.eq(id))
                .fetch(AI_USER_MODEL_ACCESS.AI_MODEL_ID).stream()
                .map(value -> new AiModelId(value.toBigInteger()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<AiModelId, Set<String>> reasoning = new LinkedHashMap<>();
        tx.select(AI_USER_MODEL_REASONING_ACCESS.AI_MODEL_ID,
                        AI_USER_MODEL_REASONING_ACCESS.REASONING_EFFORT)
                .from(AI_USER_MODEL_REASONING_ACCESS)
                .where(AI_USER_MODEL_REASONING_ACCESS.APP_USER_ID.eq(id))
                .orderBy(AI_USER_MODEL_REASONING_ACCESS.AI_MODEL_ID,
                        AI_USER_MODEL_REASONING_ACCESS.REASONING_EFFORT)
                .forEach(record -> reasoning.computeIfAbsent(
                                new AiModelId(record.get(
                                        AI_USER_MODEL_REASONING_ACCESS.AI_MODEL_ID).toBigInteger()),
                                ignored -> new LinkedHashSet<>())
                        .add(record.get(AI_USER_MODEL_REASONING_ACCESS.REASONING_EFFORT)));

        return Optional.of(new AiUserPolicy(userId,
                row.getAiEnabled() != 0,
                AiModelAccessMode.valueOf(row.getModelAccessMode()),
                row.getDefaultAiModelId() != null
                        ? new AiModelId(row.getDefaultAiModelId().toBigInteger()) : null,
                row.getMultiAgentEnabled() != 0,
                row.getMaxAgentsPerRequest().intValue(),
                row.getMaxActiveRequests().intValue(),
                nullableLong(row.getMaxOutputTokensPerCall()),
                nullableLong(row.getMaxTotalTokensPerRequest()),
                row.getQuotaPeriod() != null ? AiQuotaPeriod.valueOf(row.getQuotaPeriod()) : null,
                nullableLong(row.getQuotaTokens()), row.getPolicyVersion().longValue(),
                allowedModels, reasoning));
    }

    @Override
    public AiUserPolicy save(AiUserPolicy policy, UserId actorUserId,
                             Long expectedVersion) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            ULong target = unsigned(policy.userId());
            AiUserPolicy before = find(tx, policy.userId()).orElse(null);
            if (before == null) {
                if (expectedVersion != null && expectedVersion > 0) {
                    throw new AiPolicyVersionConflictException();
                }
                insert(tx, policy, actorUserId);
            } else {
                if (expectedVersion == null || expectedVersion != before.policyVersion()) {
                    throw new AiPolicyVersionConflictException();
                }
                int updated = tx.update(AI_USER_POLICY)
                        .set(AI_USER_POLICY.AI_ENABLED, flag(policy.aiEnabled()))
                        .set(AI_USER_POLICY.MODEL_ACCESS_MODE, policy.modelAccessMode().name())
                        .set(AI_USER_POLICY.DEFAULT_AI_MODEL_ID, unsigned(policy.defaultModelId()))
                        .set(AI_USER_POLICY.MULTI_AGENT_ENABLED, flag(policy.multiAgentEnabled()))
                        .set(AI_USER_POLICY.MAX_AGENTS_PER_REQUEST,
                                UByte.valueOf(policy.maxAgentsPerRequest()))
                        .set(AI_USER_POLICY.MAX_ACTIVE_REQUESTS,
                                UByte.valueOf(policy.maxActiveRequests()))
                        .set(AI_USER_POLICY.MAX_OUTPUT_TOKENS_PER_CALL,
                                unsigned(policy.maxOutputTokensPerCall()))
                        .set(AI_USER_POLICY.MAX_TOTAL_TOKENS_PER_REQUEST,
                                unsigned(policy.maxTotalTokensPerRequest()))
                        .set(AI_USER_POLICY.QUOTA_PERIOD,
                                policy.quotaPeriod() != null ? policy.quotaPeriod().name() : null)
                        .set(AI_USER_POLICY.QUOTA_TOKENS, unsigned(policy.quotaTokens()))
                        .set(AI_USER_POLICY.POLICY_VERSION,
                                ULong.valueOf(before.policyVersion() + 1))
                        .set(AI_USER_POLICY.LAST_UPDATED_BY, unsigned(actorUserId))
                        .set(AI_USER_POLICY.LAST_UPDATE_TIMESTAMP, now())
                        .where(AI_USER_POLICY.APP_USER_ID.eq(target))
                        .and(AI_USER_POLICY.POLICY_VERSION.eq(
                                ULong.valueOf(before.policyVersion())))
                        .execute();
                if (updated != 1) throw new AiPolicyVersionConflictException();
            }
            replaceChildren(tx, policy);
            AiUserPolicy saved = find(tx, policy.userId()).orElseThrow();
            audit(tx, policy.userId(), actorUserId, before == null ? "CREATE" : "UPDATE",
                    before, saved);
            return saved;
        });
    }

    private void insert(DSLContext tx, AiUserPolicy policy, UserId actorUserId) {
        LocalDateTime now = now();
        try {
            tx.insertInto(AI_USER_POLICY)
                .set(AI_USER_POLICY.APP_USER_ID, unsigned(policy.userId()))
                .set(AI_USER_POLICY.AI_ENABLED, flag(policy.aiEnabled()))
                .set(AI_USER_POLICY.MODEL_ACCESS_MODE, policy.modelAccessMode().name())
                .set(AI_USER_POLICY.DEFAULT_AI_MODEL_ID, unsigned(policy.defaultModelId()))
                .set(AI_USER_POLICY.MULTI_AGENT_ENABLED, flag(policy.multiAgentEnabled()))
                .set(AI_USER_POLICY.MAX_AGENTS_PER_REQUEST,
                        UByte.valueOf(policy.maxAgentsPerRequest()))
                .set(AI_USER_POLICY.MAX_ACTIVE_REQUESTS,
                        UByte.valueOf(policy.maxActiveRequests()))
                .set(AI_USER_POLICY.MAX_OUTPUT_TOKENS_PER_CALL,
                        unsigned(policy.maxOutputTokensPerCall()))
                .set(AI_USER_POLICY.MAX_TOTAL_TOKENS_PER_REQUEST,
                        unsigned(policy.maxTotalTokensPerRequest()))
                .set(AI_USER_POLICY.QUOTA_PERIOD,
                        policy.quotaPeriod() != null ? policy.quotaPeriod().name() : null)
                .set(AI_USER_POLICY.QUOTA_TOKENS, unsigned(policy.quotaTokens()))
                .set(AI_USER_POLICY.POLICY_VERSION, ULong.valueOf(1))
                .set(AI_USER_POLICY.CREATED_BY, unsigned(actorUserId))
                .set(AI_USER_POLICY.LAST_UPDATED_BY, unsigned(actorUserId))
                .set(AI_USER_POLICY.CREATION_TIMESTAMP, now)
                .set(AI_USER_POLICY.LAST_UPDATE_TIMESTAMP, now)
                    .execute();
        } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
            // A concurrent first save can pass the initial read on both nodes. The primary
            // key then provides the definitive optimistic-lock winner.
            throw new AiPolicyVersionConflictException();
        }
    }

    private void replaceChildren(DSLContext tx, AiUserPolicy policy) {
        ULong target = unsigned(policy.userId());
        tx.deleteFrom(AI_USER_MODEL_REASONING_ACCESS)
                .where(AI_USER_MODEL_REASONING_ACCESS.APP_USER_ID.eq(target)).execute();
        tx.deleteFrom(AI_USER_MODEL_ACCESS)
                .where(AI_USER_MODEL_ACCESS.APP_USER_ID.eq(target)).execute();
        policy.allowedModels().forEach(modelId -> tx.insertInto(AI_USER_MODEL_ACCESS)
                .set(AI_USER_MODEL_ACCESS.APP_USER_ID, target)
                .set(AI_USER_MODEL_ACCESS.AI_MODEL_ID, unsigned(modelId)).execute());
        policy.allowedReasoningEfforts().forEach((modelId, efforts) -> efforts.forEach(effort ->
                tx.insertInto(AI_USER_MODEL_REASONING_ACCESS)
                        .set(AI_USER_MODEL_REASONING_ACCESS.APP_USER_ID, target)
                        .set(AI_USER_MODEL_REASONING_ACCESS.AI_MODEL_ID, unsigned(modelId))
                        .set(AI_USER_MODEL_REASONING_ACCESS.REASONING_EFFORT, effort)
                        .execute()));
    }

    @Override
    public void delete(UserId targetUserId, UserId actorUserId, long expectedVersion) {
        dsl.transaction(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            AiUserPolicy before = find(tx, targetUserId)
                    .orElseThrow(AiPolicyVersionConflictException::new);
            int deleted = tx.deleteFrom(AI_USER_POLICY)
                    .where(AI_USER_POLICY.APP_USER_ID.eq(unsigned(targetUserId)))
                    .and(AI_USER_POLICY.POLICY_VERSION.eq(ULong.valueOf(expectedVersion)))
                    .execute();
            if (deleted != 1) throw new AiPolicyVersionConflictException();
            audit(tx, targetUserId, actorUserId, "DELETE", before, null);
        });
    }

    private void audit(DSLContext tx, UserId target, UserId actor, String action,
                       AiUserPolicy before, AiUserPolicy after) {
        tx.insertInto(AI_USER_POLICY_AUDIT)
                .set(AI_USER_POLICY_AUDIT.TARGET_APP_USER_ID, unsigned(target))
                .set(AI_USER_POLICY_AUDIT.ACTOR_APP_USER_ID, unsigned(actor))
                .set(AI_USER_POLICY_AUDIT.ACTION, action)
                .set(AI_USER_POLICY_AUDIT.BEFORE_JSON, json(before))
                .set(AI_USER_POLICY_AUDIT.AFTER_JSON, json(after))
                .set(AI_USER_POLICY_AUDIT.CREATION_TIMESTAMP, now())
                .execute();
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the AI policy audit snapshot.",
                    exception);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static Byte flag(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private static ULong unsigned(Id id) {
        return id != null ? ULong.valueOf(id.value()) : null;
    }

    private static ULong unsigned(Long value) {
        return value != null ? ULong.valueOf(value) : null;
    }

    private static Long nullableLong(ULong value) {
        return value != null ? value.longValue() : null;
    }
}
