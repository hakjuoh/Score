package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.MutationConfirmation;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationDecision;
import org.oagi.score.gateway.http.api.ai_management.repository.jooq.JooqAiMutationConfirmationCommandRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.jooq.JooqAiMutationConfirmationQueryRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMutationConfirmationServiceDatabaseTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AiMutationConfirmationService service;
    private ScoreUser owner;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DSLContext dslContext = DSL.using(
                new TransactionAwareDataSourceProxy(dataSource),
                SQLDialect.H2,
                new Settings().withRenderSchema(false));
        owner = user(1L, "owner");
        service = new AiMutationConfirmationService(
                requester -> new JooqAiMutationConfirmationQueryRepository(
                        dslContext, requester, null),
                requester -> new JooqAiMutationConfirmationCommandRepository(
                        dslContext, requester, null),
                new ObjectMapper());
        jdbc.execute("""
                CREATE TABLE ai_chat_conversation (
                    ai_chat_conversation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    guid VARCHAR(36) NOT NULL UNIQUE,
                    app_user_id DECIMAL(20) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE ai_chat_mutation_confirmation (
                    ai_chat_mutation_confirmation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    guid VARCHAR(36) NOT NULL UNIQUE,
                    ai_chat_conversation_id BIGINT NOT NULL,
                    request_id VARCHAR(128) NOT NULL,
                    tool_name VARCHAR(240) NOT NULL,
                    arguments_digest CHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    grant_digest CHAR(64),
                    expires_at TIMESTAMP(6) NOT NULL,
                    approved_at TIMESTAMP(6),
                    denied_at TIMESTAMP(6),
                    consumed_at TIMESTAMP(6),
                    expired_at TIMESTAMP(6),
                    created_at TIMESTAMP(6) NOT NULL)
                """);
        jdbc.update("INSERT INTO ai_chat_conversation (guid, app_user_id) VALUES (?, ?)",
                "conversation-1", BigInteger.ONE);
    }

    @Test
    void exercisesRequestedApprovedConsumedAndOneTimeReplayAgainstADatabase() {
        AiMutationAuthorization requested = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-1", null,
                "update_business_context", "{\"id\":1}"));

        assertThat(requested.allowed()).isFalse();
        String confirmationId = requested.notice().confirmationRequestId();
        AiMutationDecision approved = inTransaction(() -> service.decide(
                owner, "conversation-1", confirmationId, "APPROVE"));
        String grant = approved.response().confirmationGrant();
        assertThat(grant).isNotBlank();

        AiMutationAuthorization consumed = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-2",
                new MutationConfirmation(confirmationId, grant),
                "update_business_context", "{\"id\":1}"));
        assertThat(consumed.allowed()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_chat_mutation_confirmation WHERE guid = ?",
                String.class, confirmationId)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject(
                "SELECT grant_digest FROM ai_chat_mutation_confirmation WHERE guid = ?",
                String.class, confirmationId)).isNull();

        AiMutationAuthorization replay = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-3",
                new MutationConfirmation(confirmationId, grant),
                "update_business_context", "{\"id\":1}"));
        assertThat(replay.allowed()).isFalse();
        assertThat(replay.notice().confirmationRequestId()).isNotEqualTo(confirmationId);
    }

    @Test
    void databaseOwnershipJoinRejectsAnotherUser() {
        AiMutationAuthorization requested = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-1", null,
                "delete_business_context", "{\"id\":1}"));

        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                user(2L, "other"), "conversation-1",
                requested.notice().confirmationRequestId(), "APPROVE")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revisedApprovalRunsOneChangedInvocationOfTheOriginalToolWithoutAnotherPrompt() {
        AiMutationAuthorization requested = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-1", null,
                "create_business_context", "{\"name\":\"Original\"}"));
        String confirmationId = requested.notice().confirmationRequestId();
        String revisionPrompt = "Use the name Revised Business Context";

        AiMutationDecision approved = inTransaction(() -> service.decide(
                owner, "conversation-1", confirmationId, "APPROVE", revisionPrompt));
        String grant = approved.response().confirmationGrant();
        MutationConfirmation revised = new MutationConfirmation(
                confirmationId, grant, "create_business_context", null,
                "REVISED", revisionPrompt);

        AiMutationAuthorization wrongTool = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-wrong-tool", revised,
                "delete_business_context", "{\"id\":1}"));
        assertThat(wrongTool.allowed()).isFalse();

        MutationConfirmation tamperedPrompt = new MutationConfirmation(
                confirmationId, grant, "create_business_context", null,
                "REVISED", "Use a different revision");
        AiMutationAuthorization wrongPrompt = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-wrong-prompt", tamperedPrompt,
                "create_business_context", "{\"name\":\"Revised Business Context\"}"));
        assertThat(wrongPrompt.allowed()).isFalse();

        AiMutationAuthorization consumed = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-2", revised,
                "create_business_context", "{\"name\":\"Revised Business Context\"}"));
        assertThat(consumed.allowed()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_chat_mutation_confirmation WHERE guid = ?",
                String.class, confirmationId)).isEqualTo("CONSUMED");

        AiMutationAuthorization replay = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-3", revised,
                "create_business_context", "{\"name\":\"Another\"}"));
        assertThat(replay.allowed()).isFalse();
    }

    @Test
    void renewsAnApprovedGrantPastTheRequestExpiryAndGuardExecutesTheExactMutationOnce()
            throws Exception {
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        when(requests.mutationStarted("request-1")).thenReturn(true);
        AiMutationToolGuard guard = new AiMutationToolGuard(service, requests);
        ChatRequest request = new ChatRequest(
                "change it", "request-1", null, "conversation-1",
                null, List.of(), null, "model", "high", "ask");
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("update_business_context")
                .description("update")
                .inputSchema("{\"type\":\"object\"}")
                .build());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("updated");
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                request, owner, ignored -> { }, () -> new ToolCallback[]{delegate}, Set.of());

        String blocked = inTransaction(() -> session.getToolCallbacks()[0]
                .call("{\"id\":1}", new ToolContext(Map.of())));
        assertThat(blocked).contains(AiMutationToolGuard.MUTATION_CONFIRMATION_REQUIRED);
        String confirmationId = session.pendingApprovals().getFirst()
                .notice().confirmationRequestId();
        Instant requestExpiresAt = Instant.now().plusMillis(250);
        jdbc.update("UPDATE ai_chat_mutation_confirmation SET expires_at = ? WHERE guid = ?",
                Timestamp.from(requestExpiresAt), confirmationId);

        AiMutationDecision approved = inTransaction(() -> service.decide(
                owner, "conversation-1", confirmationId, "APPROVE"));
        assertThat(approved.response().expiresAt())
                .isAfter(requestExpiresAt.plus(Duration.ofMinutes(9)));

        long waitMillis = Math.max(1L,
                Duration.between(Instant.now(), requestExpiresAt.plusMillis(50)).toMillis());
        Thread.sleep(waitMillis);
        assertThat(Instant.now()).isAfter(requestExpiresAt);

        var resolved = inTransaction(() -> session.resolveApprovals(session, Map.of(
                confirmationId, new AiMutationApprovalResolution(
                        confirmationId, AiMutationApprovalResolution.Decision.APPROVE,
                        approved.response().confirmationGrant()))));

        assertThat(resolved).singleElement().satisfies(result -> {
            assertThat(result.executed()).isTrue();
            assertThat(result.result()).isEqualTo("updated");
        });
        assertThat(session.getToolCallbacks()[0]
                .call("{\"id\":1}", new ToolContext(Map.of())))
                .isEqualTo("updated");
        verify(delegate, times(1)).call(
                org.mockito.ArgumentMatchers.eq("{\"id\":1}"), any(ToolContext.class));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_chat_mutation_confirmation WHERE guid = ?",
                String.class, confirmationId)).isEqualTo("CONSUMED");
    }

    @Test
    void legacyDenialDuringWorkerReacquisitionCannotInvalidateACommittedGrant() {
        jdbc.update("INSERT INTO ai_chat_conversation (guid, app_user_id) VALUES (?, ?)",
                "child-conversation", BigInteger.ONE);
        AiRequestRegistry requests = new AiRequestRegistry();
        requests.register("request-1", "root-conversation", owner,
                Instant.now().plusSeconds(30));
        AiMutationAuthorization requested = inTransaction(() -> service.authorize(
                owner, "child-conversation", "request-1", null,
                "update_business_context", "{\"id\":1}"));
        String confirmationId = requested.notice().confirmationRequestId();
        assertThat(inTransaction(() -> service.sourceRequestId(
                owner, "child-conversation", confirmationId))).isEqualTo("request-1");
        AiMutationDecision approved = inTransaction(() -> service.decide(
                owner, "child-conversation", confirmationId, "APPROVE"));

        assertThatThrownBy(() -> requests.whileRequestAndConversationIdle(
                "request-1", "child-conversation", () -> inTransaction(() -> service.decide(
                        owner, "child-conversation", confirmationId, "DENY"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active AI request");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_chat_mutation_confirmation WHERE guid = ?",
                String.class, confirmationId)).isEqualTo("APPROVED");
        AiMutationAuthorization consumed = inTransaction(() -> service.authorize(
                owner, "child-conversation", "request-2",
                new MutationConfirmation(confirmationId,
                        approved.response().confirmationGrant()),
                "update_business_context", "{\"id\":1}"));
        assertThat(consumed.allowed()).isTrue();
        AiMutationAuthorization replay = inTransaction(() -> service.authorize(
                owner, "child-conversation", "request-3",
                new MutationConfirmation(confirmationId,
                        approved.response().confirmationGrant()),
                "update_business_context", "{\"id\":1}"));
        assertThat(replay.allowed()).isFalse();
    }

    @Test
    void allowsExplicitRevocationAfterTheIssuingRequestIsNoLongerActive() {
        AiMutationAuthorization requested = inTransaction(() -> service.authorize(
                owner, "conversation-1", "terminal-request", null,
                "update_business_context", "{\"id\":1}"));
        String confirmationId = requested.notice().confirmationRequestId();
        AiMutationDecision approved = inTransaction(() -> service.decide(
                owner, "conversation-1", confirmationId, "APPROVE"));

        AiMutationDecision revoked = inTransaction(() -> service.decide(
                owner, "conversation-1", confirmationId, "DENY"));

        assertThat(revoked.status()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.response().disposition()).isEqualTo("DENIED");
        AiMutationAuthorization rejectedGrant = inTransaction(() -> service.authorize(
                owner, "conversation-1", "request-2",
                new MutationConfirmation(confirmationId,
                        approved.response().confirmationGrant()),
                "update_business_context", "{\"id\":1}"));
        assertThat(rejectedGrant.allowed()).isFalse();
    }

    private <T> T inTransaction(SupplierWithResult<T> operation) {
        return transactions.execute(status -> operation.get());
    }

    private ScoreUser user(long id, String username) {
        return new ScoreUser(new UserId(BigInteger.valueOf(id)), username, username,
                null, false, List.of());
    }

    @FunctionalInterface
    private interface SupplierWithResult<T> {
        T get();
    }
}
