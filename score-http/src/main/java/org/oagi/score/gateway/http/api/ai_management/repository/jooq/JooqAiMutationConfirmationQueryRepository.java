package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationState;
import org.oagi.score.gateway.http.api.ai_management.repository.AiMutationConfirmationQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatMutationConfirmation.AI_CHAT_MUTATION_CONFIRMATION;

/**
 * JOOQ read-side implementation of {@link AiMutationConfirmationQueryRepository}.
 * Ownership predicates and {@code FOR UPDATE} locks are applied in the same query
 * so confirmation state cannot be disclosed or transitioned across users.
 */
public class JooqAiMutationConfirmationQueryRepository extends JooqBaseRepository
        implements AiMutationConfirmationQueryRepository {

    @Override
    public Optional<AiMutationConfirmationState> findOwned(
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
    public JooqAiMutationConfirmationQueryRepository(
            DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, Objects.requireNonNull(requester, "requester"), repositoryFactory);
    }

    @Override
    public Optional<AiMutationConfirmationState> findOwnedForUpdate(
            String conversationId,
            String confirmationRequestId) {
        return ownedState(conversationId, confirmationRequestId)
                .forUpdate()
                .fetchOptional(this::state);
    }

    private org.jooq.SelectConditionStep<? extends Record> ownedState(
            String conversationId, String confirmationRequestId) {
        return selectState()
                .where(AI_CHAT_MUTATION_CONFIRMATION.GUID.eq(confirmationRequestId)
                        .and(AI_CHAT_CONVERSATION.GUID.eq(conversationId))
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId())));
    }

    @Override
    public Optional<AiMutationConfirmationState> findReusableForUpdate(
            String conversationId,
            String requestId,
            String toolName,
            String argumentsDigest,
            Instant now) {
        return selectState()
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId()))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.REQUEST_ID.eq(requestId))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.TOOL_NAME.eq(toolName))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.ARGUMENTS_DIGEST.eq(argumentsDigest))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.STATUS.in("REQUESTED", "APPROVED"))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT.gt(localDateTime(now))))
                .orderBy(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID.desc())
                .limit(1)
                .forUpdate()
                .fetchOptional(this::state);
    }

    private org.jooq.SelectJoinStep<? extends Record> selectState() {
        return dslContext().select(
                        AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID,
                        AI_CHAT_MUTATION_CONFIRMATION.GUID,
                        AI_CHAT_MUTATION_CONFIRMATION.REQUEST_ID,
                        AI_CHAT_MUTATION_CONFIRMATION.STATUS,
                        AI_CHAT_MUTATION_CONFIRMATION.TOOL_NAME,
                        AI_CHAT_MUTATION_CONFIRMATION.ARGUMENTS_DIGEST,
                        AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.APPROVED_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.DENIED_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.EXPIRED_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.CONSUMED_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST)
                .from(AI_CHAT_MUTATION_CONFIRMATION)
                .join(AI_CHAT_CONVERSATION)
                .on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_CONVERSATION_ID));
    }

    private AiMutationConfirmationState state(Record record) {
        return new AiMutationConfirmationState(
                record.get(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID).longValue(),
                record.get(AI_CHAT_MUTATION_CONFIRMATION.GUID),
                record.get(AI_CHAT_MUTATION_CONFIRMATION.REQUEST_ID),
                record.get(AI_CHAT_MUTATION_CONFIRMATION.STATUS),
                record.get(AI_CHAT_MUTATION_CONFIRMATION.TOOL_NAME),
                record.get(AI_CHAT_MUTATION_CONFIRMATION.ARGUMENTS_DIGEST),
                instant(record.get(AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT)),
                instant(record.get(AI_CHAT_MUTATION_CONFIRMATION.APPROVED_AT)),
                instant(record.get(AI_CHAT_MUTATION_CONFIRMATION.DENIED_AT)),
                instant(record.get(AI_CHAT_MUTATION_CONFIRMATION.EXPIRED_AT)),
                instant(record.get(AI_CHAT_MUTATION_CONFIRMATION.CONSUMED_AT)),
                record.get(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST));
    }

    private ULong userId() {
        return ULong.valueOf(requester().userId().value());
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant instant(LocalDateTime value) {
        return value != null ? value.atZone(ZoneId.systemDefault()).toInstant() : null;
    }
}
