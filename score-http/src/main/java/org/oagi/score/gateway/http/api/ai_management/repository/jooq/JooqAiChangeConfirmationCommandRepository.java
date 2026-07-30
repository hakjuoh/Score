package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.model.CreateAiChangeConfirmationArguments;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChangeConfirmationCommandRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

import static org.jooq.impl.DSL.val;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatChangeConfirmation.AI_CHAT_CHANGE_CONFIRMATION;

/**
 * JOOQ write-side implementation of {@link AiChangeConfirmationCommandRepository}.
 * Every lifecycle update includes its expected source status to preserve one-time
 * grant semantics under concurrent requests.
 */
public class JooqAiChangeConfirmationCommandRepository extends JooqBaseRepository
        implements AiChangeConfirmationCommandRepository {

    /**
     * Creates a requester-bound repository with the application JOOQ context.
     *
     * @param dslContext context used to execute generated-model commands
     * @param requester signed-in conversation owner
     * @param repositoryFactory factory for related database repositories
     */
    public JooqAiChangeConfirmationCommandRepository(
            DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, Objects.requireNonNull(requester, "requester"), repositoryFactory);
    }

    @Override
    public boolean create(String conversationId,
                          CreateAiChangeConfirmationArguments arguments) {
        return dslContext().insertInto(AI_CHAT_CHANGE_CONFIRMATION,
                        AI_CHAT_CHANGE_CONFIRMATION.GUID,
                        AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CHANGE_CONFIRMATION.REQUEST_ID,
                        AI_CHAT_CHANGE_CONFIRMATION.TOOL_NAME,
                        AI_CHAT_CHANGE_CONFIRMATION.ARGUMENTS_DIGEST,
                        AI_CHAT_CHANGE_CONFIRMATION.STATUS,
                        AI_CHAT_CHANGE_CONFIRMATION.EXPIRES_AT,
                        AI_CHAT_CHANGE_CONFIRMATION.CREATED_AT)
                .select(dslContext().select(
                                val(arguments.guid()),
                                AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                                val(arguments.requestId()),
                                val(arguments.toolName()),
                                val(arguments.argumentsDigest()),
                                val("REQUESTED"),
                                val(localDateTime(arguments.expiresAt())),
                                val(localDateTime(arguments.createdAt())))
                        .from(AI_CHAT_CONVERSATION)
                        .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                                .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId()))))
                .execute() == 1;
    }

    @Override
    public boolean markExpired(long confirmationId, Instant expiredAt) {
        return dslContext().update(AI_CHAT_CHANGE_CONFIRMATION)
                .set(AI_CHAT_CHANGE_CONFIRMATION.STATUS, "EXPIRED")
                .set(AI_CHAT_CHANGE_CONFIRMATION.EXPIRED_AT, localDateTime(expiredAt))
                .setNull(AI_CHAT_CHANGE_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId)))
                .execute() == 1;
    }

    @Override
    public boolean approve(long confirmationId, String grantDigest, Instant approvedAt,
                           Instant grantExpiresAt, String argumentsDigest) {
        return dslContext().update(AI_CHAT_CHANGE_CONFIRMATION)
                .set(AI_CHAT_CHANGE_CONFIRMATION.STATUS, "APPROVED")
                .set(AI_CHAT_CHANGE_CONFIRMATION.GRANT_DIGEST, grantDigest)
                .set(AI_CHAT_CHANGE_CONFIRMATION.APPROVED_AT, localDateTime(approvedAt))
                .set(AI_CHAT_CHANGE_CONFIRMATION.EXPIRES_AT, localDateTime(grantExpiresAt))
                .set(AI_CHAT_CHANGE_CONFIRMATION.ARGUMENTS_DIGEST, argumentsDigest)
                .where(AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.STATUS.eq("REQUESTED")))
                .execute() == 1;
    }

    @Override
    public boolean deny(long confirmationId, Instant deniedAt) {
        return dslContext().update(AI_CHAT_CHANGE_CONFIRMATION)
                .set(AI_CHAT_CHANGE_CONFIRMATION.STATUS, "DENIED")
                .set(AI_CHAT_CHANGE_CONFIRMATION.DENIED_AT, localDateTime(deniedAt))
                .setNull(AI_CHAT_CHANGE_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.STATUS.in("REQUESTED", "APPROVED")))
                .execute() == 1;
    }

    @Override
    public boolean consume(long confirmationId, Instant consumedAt) {
        return dslContext().update(AI_CHAT_CHANGE_CONFIRMATION)
                .set(AI_CHAT_CHANGE_CONFIRMATION.STATUS, "CONSUMED")
                .set(AI_CHAT_CHANGE_CONFIRMATION.CONSUMED_AT, localDateTime(consumedAt))
                .setNull(AI_CHAT_CHANGE_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_CHANGE_CONFIRMATION.AI_CHAT_CHANGE_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId))
                        .and(AI_CHAT_CHANGE_CONFIRMATION.STATUS.eq("APPROVED")))
                .execute() == 1;
    }

    private ULong userId() {
        return ULong.valueOf(requester().userId().value());
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
