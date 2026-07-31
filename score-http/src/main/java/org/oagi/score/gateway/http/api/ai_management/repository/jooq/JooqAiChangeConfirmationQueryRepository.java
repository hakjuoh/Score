package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationState;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChangeConfirmationQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatChangeConfirmation.AI_CHAT_CHANGE_CONFIRMATION;

/**
 * JOOQ read-side implementation of {@link AiChangeConfirmationQueryRepository}.
 * Ownership predicates and {@code FOR UPDATE} locks are applied in the same query
 * so confirmation state cannot be disclosed or transitioned across users.
 */
public class JooqAiChangeConfirmationQueryRepository extends JooqBaseRepository
        implements AiChangeConfirmationQueryRepository {

    @Override
    public Optional<AiChangeConfirmationState> findOwned(
            String conversationId,
            String confirmationRequestId) {
        return ownedState(conversationId, confirmationRequestId)
                .fetchOptional(this::state);
    }

    /**
     * Creates a requester-bound repository with the application JOOQ context.
     *
     * @param dslContext context used to execute generated-model queries
     * @param requester signed-in conversation owner
     * @param repositoryFactory factory for related database repositories
     */
    public JooqAiChangeConfirmationQueryRepository(
            DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, Objects.requireNonNull(requester, "requester"), repositoryFactory);
    }

    @Override
    public Optional<AiChangeConfirmationState> findOwnedForUpdate(
            String conversationId,
            String confirmationRequestId) {
        return ownedState(conversationId, confirmationRequestId)
                .forUpdate()
                .fetchOptional(this::state);
    }

    private org.jooq.SelectConditionStep<? extends Record> ownedState(
            String conversationId, String confirmationRequestId) {
        return selectState()
                .where(AI_CHAT_CHANGE_CONFIRMATION.GUID.eq(confirmationRequestId)
                        .and(AI_CHAT_CONVERSATION.GUID.eq(conversationId))
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId()))));
    }

    @Override
    public Optional<AiChangeConfirmationState> findReusableForUpdate(
            String conversationId,
            String requestId,
            String toolName,
            String argumentsDigest,
            Instant now) {
        return selectState()
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId())))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.REQUEST_ID.eq(requestId))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.TOOL_NAME.eq(toolName))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.ARGUMENTS_DIGEST.eq(argumentsDigest))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.STATUS.in("REQUESTED", "APPROVED"))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.EXPIRES_AT.gt(localDateTime(now))))
                .orderBy(AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID.desc())
                .limit(1)
                .forUpdate()
                .fetchOptional(this::state);
    }

    private org.jooq.SelectJoinStep<? extends Record> selectState() {
        return dslContext().select(
                        AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID,
                        AI_CHAT_CHANGE_CONFIRMATION.GUID,
                        AI_CHAT_CHANGE_CONFIRMATION.REQUEST_ID,
                        AI_CHAT_CHANGE_CONFIRMATION.STATUS,
                        AI_CHAT_CHANGE_CONFIRMATION.TOOL_NAME,
                        AI_CHAT_CHANGE_CONFIRMATION.ARGUMENTS_DIGEST,
                        AI_CHAT_CHANGE_CONFIRMATION.EXPIRES_AT,
                        AI_CHAT_CHANGE_CONFIRMATION.APPROVED_AT,
                        AI_CHAT_CHANGE_CONFIRMATION.DENIED_AT,
                        AI_CHAT_CHANGE_CONFIRMATION.EXPIRED_AT,
                        AI_CHAT_CHANGE_CONFIRMATION.CONSUMED_AT,
                        AI_CHAT_CHANGE_CONFIRMATION.GRANT_DIGEST)
                .from(AI_CHAT_CHANGE_CONFIRMATION)
                .join(AI_CHAT_CONVERSATION)
                .on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CONVERSATION_ID));
    }

    private AiChangeConfirmationState state(Record record) {
        return new AiChangeConfirmationState(
                new AiChangeConfirmationId(record.get(
                        AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID).toBigInteger()),
                record.get(AI_CHAT_CHANGE_CONFIRMATION.GUID),
                record.get(AI_CHAT_CHANGE_CONFIRMATION.REQUEST_ID),
                record.get(AI_CHAT_CHANGE_CONFIRMATION.STATUS),
                record.get(AI_CHAT_CHANGE_CONFIRMATION.TOOL_NAME),
                record.get(AI_CHAT_CHANGE_CONFIRMATION.ARGUMENTS_DIGEST),
                instant(record.get(AI_CHAT_CHANGE_CONFIRMATION.EXPIRES_AT)),
                instant(record.get(AI_CHAT_CHANGE_CONFIRMATION.APPROVED_AT)),
                instant(record.get(AI_CHAT_CHANGE_CONFIRMATION.DENIED_AT)),
                instant(record.get(AI_CHAT_CHANGE_CONFIRMATION.EXPIRED_AT)),
                instant(record.get(AI_CHAT_CHANGE_CONFIRMATION.CONSUMED_AT)),
                record.get(AI_CHAT_CHANGE_CONFIRMATION.GRANT_DIGEST));
    }

    private UserId userId() {
        return requester().userId();
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant instant(LocalDateTime value) {
        return value != null ? value.atZone(ZoneId.systemDefault()).toInstant() : null;
    }
}
