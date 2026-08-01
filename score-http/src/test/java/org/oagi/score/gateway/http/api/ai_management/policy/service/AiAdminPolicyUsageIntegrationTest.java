package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.jooq.DSLContext;
import org.jooq.types.UByte;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.model.Sort;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_LEDGER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_USER_POLICY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_USER;

@SpringBootTest
class AiAdminPolicyUsageIntegrationTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private AiAdminPolicyService policies;

    @Autowired
    private AiUsageReportService usageReports;

    @Test
    void policySearchUsesPolicyMetadataSupportsEffectiveFiltersAndExcludesSystemUser() {
        String loginPrefix = "policy-it-" + UUID.randomUUID().toString().substring(0, 8);
        String loginId = loginPrefix + "-direct";
        String inheritedLoginId = loginPrefix + "-inherited";
        ULong actorId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER)
                .where(APP_USER.LOGIN_ID.ne(ScoreUser.SYSTEM_USER_LOGIN_ID))
                .orderBy(APP_USER.APP_USER_ID).limit(1).fetchOne(APP_USER.APP_USER_ID);
        assertThat(actorId).as("a non-system policy updater is required").isNotNull();
        String actorLoginId = dsl.select(APP_USER.LOGIN_ID).from(APP_USER)
                .where(APP_USER.APP_USER_ID.eq(actorId)).fetchOne(APP_USER.LOGIN_ID);
        dsl.insertInto(APP_USER).set(APP_USER.LOGIN_ID, loginId).set(APP_USER.NAME, "Policy IT")
                .set(APP_USER.ORGANIZATION, "Integration").set(APP_USER.IS_ENABLED, (byte) 1)
                .execute();
        dsl.insertInto(APP_USER).set(APP_USER.LOGIN_ID, inheritedLoginId)
                .set(APP_USER.NAME, "Inherited Policy IT")
                .set(APP_USER.ORGANIZATION, "Integration").set(APP_USER.IS_ENABLED, (byte) 1)
                .execute();
        ULong targetId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER)
                .where(APP_USER.LOGIN_ID.eq(loginId)).fetchOne(APP_USER.APP_USER_ID);
        ULong inheritedTargetId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER)
                .where(APP_USER.LOGIN_ID.eq(inheritedLoginId)).fetchOne(APP_USER.APP_USER_ID);
        LocalDateTime lastUpdatedTime = LocalDateTime.of(2096, 1, 1, 0, 0);
        ScoreUser administrator = new ScoreUser(new UserId(actorId.toBigInteger()), actorLoginId,
                actorLoginId, null, true, List.of(ScoreRole.ADMINISTRATOR));
        try {
            dsl.insertInto(AI_USER_POLICY)
                    .set(AI_USER_POLICY.APP_USER_ID, targetId)
                    .set(AI_USER_POLICY.AI_ENABLED, (byte) 1)
                    .set(AI_USER_POLICY.MODEL_ACCESS_MODE, "ALL")
                    .set(AI_USER_POLICY.MULTI_AGENT_ENABLED, (byte) 1)
                    .set(AI_USER_POLICY.MAX_AGENTS_PER_REQUEST, UByte.valueOf(4))
                    .set(AI_USER_POLICY.MAX_ACTIVE_REQUESTS, UByte.valueOf(8))
                    .set(AI_USER_POLICY.QUOTA_PERIOD, "MONTHLY")
                    .set(AI_USER_POLICY.QUOTA_TOKENS, ULong.valueOf(777))
                    .set(AI_USER_POLICY.POLICY_VERSION, ULong.valueOf(1))
                    .set(AI_USER_POLICY.CREATED_BY, actorId)
                    .set(AI_USER_POLICY.LAST_UPDATED_BY, actorId)
                    .set(AI_USER_POLICY.CREATION_TIMESTAMP, lastUpdatedTime)
                    .set(AI_USER_POLICY.LAST_UPDATE_TIMESTAMP, lastUpdatedTime).execute();

            var policy = search(administrator, loginId, null, null).getList().getFirst();
            assertThat(policy.lastUpdatedAt())
                    .isEqualTo(lastUpdatedTime.toInstant(ZoneOffset.UTC));
            assertThat(policy.updaterLoginId()).isEqualTo(actorLoginId);
            assertThat(search(administrator, loginId, null, 777L).getList()).hasSize(1);
            assertThat(policy.availableModels()).isNotEmpty();
            assertThat(search(administrator, loginId,
                    policy.availableModels().getFirst(), null).getList()).hasSize(1);
            assertThat(search(administrator, ScoreUser.SYSTEM_USER_LOGIN_ID, null, null).getList())
                    .isEmpty();

            Instant lastUpdatedAt = lastUpdatedTime.toInstant(ZoneOffset.UTC);
            var included = searchByMetadata(administrator, loginPrefix, List.of(actorLoginId),
                    lastUpdatedAt.minusSeconds(1), lastUpdatedAt.plusSeconds(1),
                    new PageRequest(0, 1,
                            List.of(new Sort("updatedOn", SortDirection.DESC))));
            assertThat(included.getLength()).isEqualTo(1);
            assertThat(included.getList()).extracting(item -> item.loginId())
                    .containsExactly(loginId);

            var excluded = searchByMetadata(administrator, loginPrefix,
                    List.of("!" + actorLoginId), null, null,
                    new PageRequest(0, 1, List.of(new Sort("loginId", SortDirection.ASC))));
            assertThat(excluded.getLength()).isEqualTo(1);
            assertThat(excluded.getList()).singleElement().satisfies(item -> {
                assertThat(item.loginId()).isEqualTo(inheritedLoginId);
                assertThat(item.inherited()).isTrue();
                assertThat(item.updaterLoginId()).isNull();
                assertThat(item.lastUpdatedAt()).isNull();
            });
        } finally {
            dsl.deleteFrom(AI_USER_POLICY).where(AI_USER_POLICY.APP_USER_ID.eq(targetId)).execute();
            dsl.deleteFrom(APP_USER).where(APP_USER.APP_USER_ID.in(targetId, inheritedTargetId))
                    .execute();
        }
    }

    @Test
    void usageReportAggregatesBoundariesAndPagesNewestFirst() {
        ULong userId = dsl.select(APP_USER.APP_USER_ID).from(APP_USER)
                .where(APP_USER.LOGIN_ID.ne(ScoreUser.SYSTEM_USER_LOGIN_ID))
                .orderBy(APP_USER.APP_USER_ID).limit(1).fetchOne(APP_USER.APP_USER_ID);
        ULong modelId = dsl.select(AI_MODEL.AI_MODEL_ID).from(AI_MODEL)
                .orderBy(AI_MODEL.AI_MODEL_ID).limit(1).fetchOne(AI_MODEL.AI_MODEL_ID);
        assertThat(userId).isNotNull();
        assertThat(modelId).isNotNull();
        String prefix = "usage-it-" + UUID.randomUUID();
        Instant start = Instant.parse("2097-03-01T00:00:00Z");
        Instant end = Instant.parse("2097-04-01T00:00:00Z");
        try {
            insertCall(prefix + "-before", userId, modelId, start.minusSeconds(1),
                    "SETTLED", 30, 30);
            insertCall(prefix + "-settled", userId, modelId, start.plusSeconds(1),
                    "SETTLED", 100, 10);
            insertCall(prefix + "-reserved", userId, modelId, end.minusSeconds(1),
                    "RESERVED", 20, 0);
            insertCall(prefix + "-after", userId, modelId, end,
                    "SETTLED", 40, 40);

            var first = usageReports.load(new UserId(userId.toBigInteger()), start, end,
                    new PageRequest(0, 1, List.of()));
            assertThat(first.usage().modelCalls()).isEqualTo(2);
            assertThat(first.usage().chargedTokens()).isEqualTo(10);
            assertThat(first.usage().reservedTokens()).isEqualTo(20);
            assertThat(first.calls().getLength()).isEqualTo(2);
            assertThat(first.calls().getList()).extracting(entry -> entry.status())
                    .containsExactly("RESERVED");

            var second = usageReports.load(new UserId(userId.toBigInteger()), start, end,
                    new PageRequest(1, 1, List.of()));
            assertThat(second.calls().getList()).extracting(entry -> entry.status())
                    .containsExactly("SETTLED");

            var chargedAscending = usageReports.load(new UserId(userId.toBigInteger()), start, end,
                    new PageRequest(0, 10,
                            List.of(new Sort("charged", SortDirection.ASC))));
            assertThat(chargedAscending.calls().getList())
                    .extracting(entry -> entry.chargedTokens()).containsExactly(0L, 10L);
        } finally {
            dsl.deleteFrom(AI_TOKEN_USAGE_LEDGER)
                    .where(AI_TOKEN_USAGE_LEDGER.REQUEST_ID.like(prefix + "%")).execute();
        }
    }

    private org.oagi.score.gateway.http.common.model.PageResponse<
            org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUserSummary> search(
            ScoreUser actor, String loginId, String model, Long quotaTokens) {
        return policies.searchUsers(actor, loginId, null, null, null, model, null, quotaTokens,
                null, List.of(), null, null, new PageRequest(0, 10, List.of()));
    }

    private org.oagi.score.gateway.http.common.model.PageResponse<
            org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUserSummary>
    searchByMetadata(ScoreUser actor, String loginId, List<String> updaterLoginIds,
                     Instant updatedAfter, Instant updatedBefore, PageRequest pageRequest) {
        return policies.searchUsers(actor, loginId, null, null, null, null, null, null,
                null, updaterLoginIds, updatedAfter, updatedBefore, pageRequest);
    }

    private void insertCall(String requestId, ULong userId, ULong modelId, Instant timestamp,
                            String status, long reservedTokens, long chargedTokens) {
        dsl.insertInto(AI_TOKEN_USAGE_LEDGER)
                .set(AI_TOKEN_USAGE_LEDGER.CALL_ID, UUID.randomUUID().toString())
                .set(AI_TOKEN_USAGE_LEDGER.REQUEST_ID, requestId)
                .set(AI_TOKEN_USAGE_LEDGER.APP_USER_ID, userId)
                .set(AI_TOKEN_USAGE_LEDGER.AI_MODEL_ID, modelId)
                .set(AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND, "assistant")
                .set(AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS, ULong.valueOf(reservedTokens))
                .set(AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS, ULong.valueOf(chargedTokens))
                .set(AI_TOKEN_USAGE_LEDGER.USAGE_COMPLETE, (byte) ("RESERVED".equals(status) ? 0 : 1))
                .set(AI_TOKEN_USAGE_LEDGER.STATUS, status)
                .set(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP,
                        timestamp.atZone(ZoneOffset.UTC).toLocalDateTime())
                .set(AI_TOKEN_USAGE_LEDGER.SETTLED_TIMESTAMP, "RESERVED".equals(status) ? null
                        : timestamp.atZone(ZoneOffset.UTC).toLocalDateTime())
                .execute();
    }
}
