package org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaWindow;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiCallReservation;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUsageSettlement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiQuotaExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_REQUEST_USAGE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_LEDGER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_PERIOD;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_USER;

@SpringBootTest(properties =
        "score.security.secret-encryption.keys.primary=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class JooqAiQuotaRepositoryIntegrationTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private JooqAiQuotaRepository quotas;

    @Test
    void settlesOverlappingDailyAndMonthlyWindowsByTheirExactCompositeIdentity() {
        ULong userId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER).orderBy(APP_USER.APP_USER_ID)
                .limit(1).fetchOne(APP_USER.APP_USER_ID);
        ULong modelId = dsl.select(AI_MODEL.AI_MODEL_ID).from(AI_MODEL).orderBy(AI_MODEL.AI_MODEL_ID)
                .limit(1).fetchOne(AI_MODEL.AI_MODEL_ID);
        assertThat(userId).as("an application user is required").isNotNull();
        assertThat(modelId).as("an AI model is required").isNotNull();

        Instant start = Instant.parse("2099-01-01T00:00:00Z");
        AiQuotaWindow daily = new AiQuotaWindow(start,
                Instant.parse("2099-01-02T00:00:00Z"), 1_000L);
        AiQuotaWindow monthly = new AiQuotaWindow(start,
                Instant.parse("2099-02-01T00:00:00Z"), 2_000L);
        UUID dailyCall = UUID.randomUUID();
        UUID monthlyCall = UUID.randomUUID();
        String dailyRequest = "quota-daily-" + UUID.randomUUID();
        String monthlyRequest = "quota-monthly-" + UUID.randomUUID();
        UserId owner = new UserId(userId.toBigInteger());

        try {
            quotas.reserve(dailyCall, dailyRequest, owner, modelId.longValue(), null,
                    "ROOT", "assistant", 10L, 20, true, null, daily);
            quotas.settle(dailyCall, new AiUsageSettlement(11L, 5L, 0L, true));
            quotas.reserve(monthlyCall, monthlyRequest, owner, modelId.longValue(), null,
                    "ROOT", "assistant", 10L, 20, true, null, monthly);
            quotas.settle(monthlyCall, new AiUsageSettlement(12L, 6L, 0L, true));

            LocalDateTime localStart = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
            var rows = dsl.select(AI_TOKEN_USAGE_PERIOD.PERIOD_END,
                            AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS,
                            AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS)
                    .from(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(userId))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(localStart))
                    .orderBy(AI_TOKEN_USAGE_PERIOD.PERIOD_END)
                    .fetch();

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS).longValue())
                    .isEqualTo(16L);
            assertThat(rows.get(1).get(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS).longValue())
                    .isEqualTo(18L);
            assertThat(rows).allSatisfy(row -> assertThat(
                    row.get(AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS).longValue()).isZero());
        } finally {
            dsl.deleteFrom(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.in(dailyRequest, monthlyRequest))
                    .execute();
            dsl.deleteFrom(AI_TOKEN_REQUEST_USAGE)
                    .where(AI_TOKEN_REQUEST_USAGE.REQUEST_ID.in(dailyRequest, monthlyRequest))
                    .execute();
            dsl.deleteFrom(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(userId))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(
                            LocalDateTime.ofInstant(start, ZoneOffset.UTC)))
                    .execute();
        }
    }

    @Test
    void concurrentReservationsCannotExceedTheSharedPeriodAndSettlementIsIdempotent()
            throws Exception {
        ULong userId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER)
                .orderBy(APP_USER.APP_USER_ID).limit(1).fetchOne(APP_USER.APP_USER_ID);
        ULong modelId = dsl.select(AI_MODEL.AI_MODEL_ID).from(AI_MODEL)
                .orderBy(AI_MODEL.AI_MODEL_ID).limit(1).fetchOne(AI_MODEL.AI_MODEL_ID);
        assertThat(userId).isNotNull();
        assertThat(modelId).isNotNull();
        Instant start = Instant.parse("2098-03-01T00:00:00Z");
        AiQuotaWindow window = new AiQuotaWindow(start,
                Instant.parse("2098-04-01T00:00:00Z"), 100L);
        String prefix = "quota-race-" + UUID.randomUUID();
        UserId owner = new UserId(userId.toBigInteger());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startRace = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index ->
                    executor.submit(() -> {
                        ready.countDown();
                        startRace.await(10, TimeUnit.SECONDS);
                        try {
                            quotas.reserve(UUID.randomUUID(), prefix + "-" + index, owner,
                                    modelId.longValue(), null, "ROOT", "assistant",
                                    10L, 60, true, null, window);
                            admitted.incrementAndGet();
                        } catch (AiQuotaExceededException expected) {
                            // A transaction may be rejected if no output token remains.
                        }
                        return null;
                    })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            startRace.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertThat(admitted).hasValue(2);
            assertThat(dsl.select(AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS)
                    .from(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(userId))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(
                            LocalDateTime.ofInstant(start, ZoneOffset.UTC)))
                    .fetchOne(AI_TOKEN_USAGE_PERIOD.RESERVED_TOKENS).longValue())
                    .isEqualTo(100L);

            String admittedRequest = dsl.select(AI_TOKEN_USAGE_LEDGER.REQUEST_ID)
                    .from(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.like(prefix + "%"))
                    .orderBy(AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS.desc())
                    .limit(1).fetchOne(AI_TOKEN_USAGE_LEDGER.REQUEST_ID);
            UUID callId = UUID.fromString(dsl.select(AI_TOKEN_USAGE_LEDGER.CALL_ID)
                    .from(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.eq(admittedRequest))
                    .fetchOne(AI_TOKEN_USAGE_LEDGER.CALL_ID));
            assertThat(quotas.settle(callId,
                    new AiUsageSettlement(12L, 8L, 0L, true))).isTrue();
            assertThat(quotas.settle(callId,
                    new AiUsageSettlement(12L, 8L, 0L, true))).isFalse();
            assertThat(dsl.select(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS)
                    .from(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(userId))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(
                            LocalDateTime.ofInstant(start, ZoneOffset.UTC)))
                    .fetchOne(AI_TOKEN_USAGE_PERIOD.CONSUMED_TOKENS).longValue())
                    .isEqualTo(20L);
        } finally {
            executor.shutdownNow();
            dsl.deleteFrom(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.like(prefix + "%")).execute();
            dsl.deleteFrom(AI_TOKEN_REQUEST_USAGE)
                    .where(AI_TOKEN_REQUEST_USAGE.REQUEST_ID.like(prefix + "%")).execute();
            dsl.deleteFrom(AI_TOKEN_USAGE_PERIOD)
                    .where(AI_TOKEN_USAGE_PERIOD.APP_USER_ID.eq(userId))
                    .and(AI_TOKEN_USAGE_PERIOD.PERIOD_START.eq(
                            LocalDateTime.ofInstant(start, ZoneOffset.UTC))).execute();
        }
    }

    @Test
    void reconciliationPagesPastMoreThanTenActiveStaleReservations() {
        ULong userId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER)
                .orderBy(APP_USER.APP_USER_ID).limit(1).fetchOne(APP_USER.APP_USER_ID);
        ULong modelId = dsl.select(AI_MODEL.AI_MODEL_ID).from(AI_MODEL)
                .orderBy(AI_MODEL.AI_MODEL_ID).limit(1).fetchOne(AI_MODEL.AI_MODEL_ID);
        assertThat(userId).isNotNull();
        assertThat(modelId).isNotNull();
        String prefix = "quota-reconcile-" + UUID.randomUUID();
        UserId owner = new UserId(userId.toBigInteger());
        try {
            for (int index = 0; index < 12; index++) {
                String requestId = prefix + "-" + index;
                AiCallReservation reservation = quotas.reserve(UUID.randomUUID(), requestId,
                        owner, modelId.longValue(), null, "ROOT", "assistant",
                        1L, 1, true, null, null);
                dsl.update(AI_TOKEN_USAGE_LEDGER)
                        .set(AI_TOKEN_USAGE_LEDGER.RESERVED_AT,
                                LocalDateTime.now(ZoneOffset.UTC).minusHours(3).plusSeconds(index))
                        .where(AI_TOKEN_USAGE_LEDGER.CALL_ID.eq(reservation.callId().toString()))
                        .execute();
            }

            var result = quotas.reconcileStaleDetailed(java.time.Duration.ofMinutes(1), 1,
                    requestId -> !requestId.endsWith("-11"));

            assertThat(result).isEqualTo(new JooqAiQuotaRepository.ReconciliationResult(1, 2L));
            assertThat(dsl.select(AI_TOKEN_USAGE_LEDGER.STATUS)
                    .from(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.eq(prefix + "-11"))
                    .fetchOne(AI_TOKEN_USAGE_LEDGER.STATUS)).isEqualTo("ESTIMATED");
        } finally {
            dsl.deleteFrom(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.like(prefix + "%")).execute();
            dsl.deleteFrom(AI_TOKEN_REQUEST_USAGE)
                    .where(AI_TOKEN_REQUEST_USAGE.REQUEST_ID.like(prefix + "%")).execute();
        }
    }
}
