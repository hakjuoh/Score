package org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiQuotaExceededException;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiCallReservation;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaWindow;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUsageSettlement;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_REQUEST_USAGE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_LEDGER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_PERIOD;

/** Atomic request/period reservation and append-only provider-attempt ledger. */
@Repository
public class JooqAiQuotaRepository {

    private final DSLContext dsl;

    public JooqAiQuotaRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public AiCallReservation reserve(UUID callId, String requestId, UserId userId,
                                     AiModelId modelId, String conversationId,
                                     String executionKind, String agentId,
                                     long estimatedInputTokens, int requestedMaxOutput,
                                     boolean enforceOutputLimit, Long requestLimit,
                                     AiQuotaWindow window) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            LocalDateTime now = now();
            ULong owner = ULong.valueOf(userId.value());
            int requestInserted = tx.insertInto(AI_TOKEN_REQUEST_USAGE)
                    .set(AI_TOKEN_REQUEST_USAGE.REQUEST_ID, requestId)
                    .set(AI_TOKEN_REQUEST_USAGE.APP_USER_ID, owner)
                    .set(AI_TOKEN_REQUEST_USAGE.CREATION_TIMESTAMP, now)
                    .set(AI_TOKEN_REQUEST_USAGE.LAST_UPDATE_TIMESTAMP, now)
                    .onDuplicateKeyIgnore().execute();
            var request = tx.selectFrom(AI_TOKEN_REQUEST_USAGE)
                    .where(AI_TOKEN_REQUEST_USAGE.REQUEST_ID.eq(requestId))
                    .forUpdate().fetchOne();
            if (request == null || !owner.equals(request.getAppUserId())) {
                throw new IllegalStateException("The AI request quota counter has an invalid owner.");
            }

            org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiTokenUsagePeriodRecord period = null;
            int periodInserted = 0;
            if (window != null) {
                LocalDateTime start = local(window.start());
                periodInserted = tx.insertInto(AI_TOKEN_USAGE_PERIOD)
                        .set(AI_TOKEN_USAGE_PERIOD.APP_USER_ID, owner)
                        .set(AI_TOKEN_USAGE_PERIOD.PERIOD_START_TIMESTAMP, start)
                        .set(AI_TOKEN_USAGE_PERIOD.PERIOD_END_TIMESTAMP, local(window.end()))
                        .set(AI_TOKEN_USAGE_PERIOD.CREATION_TIMESTAMP, now)
                        .set(AI_TOKEN_USAGE_PERIOD.LAST_UPDATE_TIMESTAMP, now)
                        .onDuplicateKeyIgnore().execute();
                period = tx.selectFrom(AI_TOKEN_USAGE_PERIOD)
                        .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(owner))
                        .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START_TIMESTAMP.eq(start))
                        .and(AI_TOKEN_USAGE_PERIOD.PERIOD_END_TIMESTAMP.eq(local(window.end())))
                        .forUpdate().fetchOne();
            }

            LocalDateTime mutationTimestamp = now();
            LocalDateTime requestUpdateTimestamp = requestInserted == 1
                    ? now : mutationTimestamp;
            LocalDateTime periodUpdateTimestamp = periodInserted == 1
                    ? now : mutationTimestamp;

            long requestAvailable = remaining(requestLimit,
                    request.getConsumedTokens().longValue(), request.getReservedTokens().longValue());
            long periodAvailable = period != null ? remaining(window.limitTokens(),
                    period.getConsumedTokens().longValue(), period.getReservedTokens().longValue())
                    : Long.MAX_VALUE;
            long available = Math.min(requestAvailable, periodAvailable);
            long outputAvailable = available == Long.MAX_VALUE ? requestedMaxOutput
                    : Math.min(requestedMaxOutput, available - estimatedInputTokens);
            if (outputAvailable < 1) {
                boolean periodExhausted = periodAvailable <= requestAvailable;
                throw exceeded(periodExhausted, window);
            }
            int effectiveOutput = (int) Math.min(Integer.MAX_VALUE, outputAvailable);
            long reserved = Math.addExact(estimatedInputTokens, effectiveOutput);

            tx.insertInto(AI_TOKEN_USAGE_LEDGER)
                    .set(AI_TOKEN_USAGE_LEDGER.CALL_ID, callId.toString())
                    .set(AI_TOKEN_USAGE_LEDGER.REQUEST_ID, requestId)
                    .set(AI_TOKEN_USAGE_LEDGER.CONVERSATION_GUID, conversationId)
                    .set(AI_TOKEN_USAGE_LEDGER.APP_USER_ID, owner)
                    .set(AI_TOKEN_USAGE_LEDGER.AI_MODEL_ID, ULong.valueOf(modelId.value()))
                    .set(AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND, executionKind)
                    .set(AI_TOKEN_USAGE_LEDGER.AGENT_ID, agentId)
                    .set(AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS, ULong.valueOf(reserved))
                    .set(AI_TOKEN_USAGE_LEDGER.QUOTA_PERIOD_START_TIMESTAMP,
                            window != null ? local(window.start()) : null)
                    .set(AI_TOKEN_USAGE_LEDGER.QUOTA_PERIOD_END_TIMESTAMP,
                            window != null ? local(window.end()) : null)
                    .set(AI_TOKEN_USAGE_LEDGER.STATUS, "RESERVED")
                    .set(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP, mutationTimestamp)
                    .execute();
            request.setReservedTokens(ULong.valueOf(
                    Math.addExact(request.getReservedTokens().longValue(), reserved)));
            request.setLastUpdateTimestamp(requestUpdateTimestamp);
            request.update();
            if (period != null) {
                period.setReservedTokens(ULong.valueOf(
                        Math.addExact(period.getReservedTokens().longValue(), reserved)));
                period.setLastUpdateTimestamp(periodUpdateTimestamp);
                period.update();
            }
            return new AiCallReservation(callId, requestId, userId, reserved,
                    enforceOutputLimit || available != Long.MAX_VALUE ? effectiveOutput : null,
                    window);
        });
    }

    public boolean settle(UUID callId, AiUsageSettlement usage) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            var ledger = tx.selectFrom(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.CALL_ID.eq(callId.toString()))
                    .forUpdate().fetchOne();
            if (ledger == null || !"RESERVED".equals(ledger.getStatus())) return false;
            long reserved = ledger.getReservedTokens().longValue();
            long charged = usage.complete()
                    ? Math.addExact(usage.promptTokens(), usage.completionTokens()) : reserved;
            LocalDateTime now = now();

            var request = tx.selectFrom(AI_TOKEN_REQUEST_USAGE)
                    .where(AI_TOKEN_REQUEST_USAGE.REQUEST_ID.eq(ledger.getRequestId()))
                    .forUpdate().fetchOne();
            updateCounter(request.getReservedTokens().longValue(),
                    request.getConsumedTokens().longValue(), reserved, charged,
                    request::setReservedTokens, request::setConsumedTokens);
            request.setLastUpdateTimestamp(now);
            request.update();

            LocalDateTime periodStart = ledger.getQuotaPeriodStartTimestamp();
            var period = periodStart != null ? tx.selectFrom(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(ledger.getAppUserId()))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START_TIMESTAMP.eq(periodStart))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_END_TIMESTAMP.eq(
                            ledger.getQuotaPeriodEndTimestamp()))
                    .forUpdate().fetchOne() : null;
            if (period != null) {
                updateCounter(period.getReservedTokens().longValue(),
                        period.getConsumedTokens().longValue(), reserved, charged,
                        period::setReservedTokens, period::setConsumedTokens);
                period.setLastUpdateTimestamp(now);
                period.update();
            }
            ledger.setPromptTokens(ULong.valueOf(usage.promptTokens()));
            ledger.setCompletionTokens(ULong.valueOf(usage.completionTokens()));
            ledger.setCachedTokens(ULong.valueOf(usage.cachedTokens()));
            ledger.setChargedTokens(ULong.valueOf(charged));
            ledger.setUsageComplete((byte) (usage.complete() ? 1 : 0));
            ledger.setStatus(usage.complete() ? "SETTLED" : "ESTIMATED");
            ledger.setSettledTimestamp(now);
            ledger.update();
            return true;
        });
    }

    public boolean release(UUID callId, String failureType) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            var ledger = tx.selectFrom(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.CALL_ID.eq(callId.toString()))
                    .forUpdate().fetchOne();
            if (ledger == null || !"RESERVED".equals(ledger.getStatus())) return false;
            long reserved = ledger.getReservedTokens().longValue();
            LocalDateTime now = now();
            var request = tx.selectFrom(AI_TOKEN_REQUEST_USAGE)
                    .where(AI_TOKEN_REQUEST_USAGE.REQUEST_ID.eq(ledger.getRequestId()))
                    .forUpdate().fetchOne();
            requireReserved(request.getReservedTokens().longValue(), reserved);
            request.setReservedTokens(ULong.valueOf(
                    request.getReservedTokens().longValue() - reserved));
            request.setLastUpdateTimestamp(now);
            request.update();
            LocalDateTime periodStart = ledger.getQuotaPeriodStartTimestamp();
            var period = periodStart != null ? tx.selectFrom(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(ledger.getAppUserId()))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START_TIMESTAMP.eq(periodStart))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_END_TIMESTAMP.eq(
                            ledger.getQuotaPeriodEndTimestamp()))
                    .forUpdate().fetchOne() : null;
            if (period != null) {
                requireReserved(period.getReservedTokens().longValue(), reserved);
                period.setReservedTokens(ULong.valueOf(
                        period.getReservedTokens().longValue() - reserved));
                period.setLastUpdateTimestamp(now);
                period.update();
            }
            ledger.setStatus("RELEASED");
            ledger.setFailureType(failureType != null
                    ? failureType.substring(0, Math.min(240, failureType.length())) : null);
            ledger.setSettledTimestamp(now);
            ledger.update();
            return true;
        });
    }

    /** Conservatively charges abandoned reservations while holding a cluster-wide DB lock. */
    public int reconcileStale(Duration timeout, int batchSize,
                              java.util.function.Predicate<String> requestIsActive) {
        return reconcileStaleDetailed(timeout, batchSize, requestIsActive).count();
    }

    /** Returns both repaired rows and the reservation tokens conservatively consumed. */
    public ReconciliationResult reconcileStaleDetailed(
            Duration timeout, int batchSize,
            java.util.function.Predicate<String> requestIsActive) {
        return dsl.connectionResult(connection -> {
            DSLContext lockDsl = org.jooq.impl.DSL.using(connection, dsl.dialect());
            Number acquired = (Number) lockDsl.fetchValue(
                    "SELECT GET_LOCK('score_ai_quota_reconciliation', 0)");
            if (acquired == null || acquired.intValue() != 1) return new ReconciliationResult(0, 0L);
            try {
                LocalDateTime cutoff = now().minus(timeout);
                int reconciled = 0;
                long consumedTokens = 0L;
                LocalDateTime cursorTime = null;
                String cursorCallId = null;
                int pageSize = Math.max(batchSize, Math.min(1000, batchSize * 10));
                while (reconciled < batchSize) {
                    var condition = AI_TOKEN_USAGE_LEDGER.STATUS.eq("RESERVED")
                            .and(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.lt(cutoff));
                    if (cursorTime != null) {
                        condition = condition.and(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.gt(cursorTime)
                                .or(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.eq(cursorTime)
                                        .and(AI_TOKEN_USAGE_LEDGER.CALL_ID.gt(cursorCallId))));
                    }
                    var rows = dsl.select(AI_TOKEN_USAGE_LEDGER.CALL_ID,
                                    AI_TOKEN_USAGE_LEDGER.REQUEST_ID,
                                    AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP,
                                    AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS)
                            .from(AI_TOKEN_USAGE_LEDGER)
                            .where(condition)
                            .orderBy(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.asc(),
                                    AI_TOKEN_USAGE_LEDGER.CALL_ID.asc())
                            .limit(pageSize).fetch();
                    if (rows.isEmpty()) break;
                    for (var row : rows) {
                        cursorTime = row.get(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP);
                        cursorCallId = row.get(AI_TOKEN_USAGE_LEDGER.CALL_ID);
                        String requestId = row.get(AI_TOKEN_USAGE_LEDGER.REQUEST_ID);
                        if (requestId != null && requestIsActive.test(requestId)) continue;
                        UUID callId = UUID.fromString(cursorCallId);
                        if (settle(callId, new AiUsageSettlement(0L, 0L, 0L, false))) {
                            reconciled++;
                            consumedTokens = Math.addExact(consumedTokens,
                                    row.get(AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS).longValue());
                            if (reconciled >= batchSize) break;
                        }
                    }
                    if (rows.size() < pageSize) break;
                }
                return new ReconciliationResult(reconciled, consumedTokens);
            } finally {
                lockDsl.execute("SELECT RELEASE_LOCK('score_ai_quota_reconciliation')");
            }
        });
    }

    public record ReconciliationResult(int count, long consumedTokens) {
    }

    public boolean isExhausted(UserId userId, AiQuotaWindow window) {
        if (window == null) return false;
        var row = dsl.select(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS,
                        AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS)
                .from(AI_TOKEN_USAGE_PERIOD)
                .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(ULong.valueOf(userId.value())))
                .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START_TIMESTAMP.eq(local(window.start())))
                .and(AI_TOKEN_USAGE_PERIOD.PERIOD_END_TIMESTAMP.eq(local(window.end())))
                .fetchOne();
        return row != null && row.get(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS).longValue()
                + row.get(AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS).longValue()
                >= window.limitTokens();
    }

    private static void updateCounter(long currentReserved, long currentConsumed,
                                      long released, long charged,
                                      java.util.function.Consumer<ULong> setReserved,
                                      java.util.function.Consumer<ULong> setConsumed) {
        requireReserved(currentReserved, released);
        setReserved.accept(ULong.valueOf(currentReserved - released));
        setConsumed.accept(ULong.valueOf(Math.addExact(currentConsumed, charged)));
    }

    private static void requireReserved(long current, long required) {
        if (current < required) {
            throw new IllegalStateException("AI quota reserved-token invariant was violated.");
        }
    }

    private static long remaining(Long limit, long consumed, long reserved) {
        if (limit == null) return Long.MAX_VALUE;
        return limit - consumed - reserved;
    }

    private static AiQuotaExceededException exceeded(boolean period, AiQuotaWindow window) {
        Duration retry = period && window != null
                ? Duration.between(java.time.Instant.now(), window.end()) : null;
        return new AiQuotaExceededException(period ? AiPolicyErrorCode.AI_QUOTA_EXHAUSTED
                : AiPolicyErrorCode.AI_REQUEST_TOKEN_LIMIT_EXHAUSTED,
                period ? "The AI token quota is exhausted."
                        : "The AI request token limit is exhausted.", retry);
    }

    private static LocalDateTime local(java.time.Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
