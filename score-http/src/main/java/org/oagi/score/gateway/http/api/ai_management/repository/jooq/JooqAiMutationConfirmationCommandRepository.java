package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.model.CreateAiMutationConfirmationArguments;
import org.oagi.score.gateway.http.api.ai_management.repository.AiMutationConfirmationCommandRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.jooq.impl.DSL.val;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatMutationConfirmation.AI_CHAT_MUTATION_CONFIRMATION;

/**
 * JOOQ write-side implementation of {@link AiMutationConfirmationCommandRepository}.
 * Every lifecycle update includes its expected source status to preserve one-time
 * grant semantics under concurrent requests.
 */
@Repository
public class JooqAiMutationConfirmationCommandRepository
        implements AiMutationConfirmationCommandRepository {

    private final DSLContext dslContext;

    /**
     * Creates the repository with the application JOOQ context.
     *
     * @param dslContext context used to execute generated-model commands
     */
    public JooqAiMutationConfirmationCommandRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    @Override
    public boolean create(ScoreUser requester, String conversationId,
                          CreateAiMutationConfirmationArguments arguments) {
        return dslContext.insertInto(AI_CHAT_MUTATION_CONFIRMATION,
                        AI_CHAT_MUTATION_CONFIRMATION.GUID,
                        AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_MUTATION_CONFIRMATION.REQUEST_ID,
                        AI_CHAT_MUTATION_CONFIRMATION.TOOL_NAME,
                        AI_CHAT_MUTATION_CONFIRMATION.ARGUMENTS_DIGEST,
                        AI_CHAT_MUTATION_CONFIRMATION.STATUS,
                        AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.CREATED_AT)
                .select(dslContext.select(
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
                                .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId(requester)))))
                .execute() == 1;
    }

    @Override
    public boolean markExpired(long confirmationId, Instant expiredAt) {
        return dslContext.update(AI_CHAT_MUTATION_CONFIRMATION)
                .set(AI_CHAT_MUTATION_CONFIRMATION.STATUS, "EXPIRED")
                .set(AI_CHAT_MUTATION_CONFIRMATION.EXPIRED_AT, localDateTime(expiredAt))
                .setNull(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId)))
                .execute() == 1;
    }

    @Override
    public boolean approve(long confirmationId, String grantDigest, Instant approvedAt,
                           String argumentsDigest) {
        return dslContext.update(AI_CHAT_MUTATION_CONFIRMATION)
                .set(AI_CHAT_MUTATION_CONFIRMATION.STATUS, "APPROVED")
                .set(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST, grantDigest)
                .set(AI_CHAT_MUTATION_CONFIRMATION.APPROVED_AT, localDateTime(approvedAt))
                .set(AI_CHAT_MUTATION_CONFIRMATION.ARGUMENTS_DIGEST, argumentsDigest)
                .where(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.STATUS.eq("REQUESTED")))
                .execute() == 1;
    }

    @Override
    public boolean deny(long confirmationId, Instant deniedAt) {
        return dslContext.update(AI_CHAT_MUTATION_CONFIRMATION)
                .set(AI_CHAT_MUTATION_CONFIRMATION.STATUS, "DENIED")
                .set(AI_CHAT_MUTATION_CONFIRMATION.DENIED_AT, localDateTime(deniedAt))
                .setNull(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.STATUS.in("REQUESTED", "APPROVED")))
                .execute() == 1;
    }

    @Override
    public boolean consume(long confirmationId, Instant consumedAt) {
        return dslContext.update(AI_CHAT_MUTATION_CONFIRMATION)
                .set(AI_CHAT_MUTATION_CONFIRMATION.STATUS, "CONSUMED")
                .set(AI_CHAT_MUTATION_CONFIRMATION.CONSUMED_AT, localDateTime(consumedAt))
                .setNull(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_MUTATION_CONFIRMATION.AI_CHAT_MUTATION_CONFIRMATION_ID
                        .eq(ULong.valueOf(confirmationId))
                        .and(AI_CHAT_MUTATION_CONFIRMATION.STATUS.eq("APPROVED")))
                .execute() == 1;
    }

    private ULong userId(ScoreUser requester) {
        return ULong.valueOf(requester.userId().value());
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
