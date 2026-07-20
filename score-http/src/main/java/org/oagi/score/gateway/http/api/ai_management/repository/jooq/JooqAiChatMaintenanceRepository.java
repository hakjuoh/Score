package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMaintenanceRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_MUTATION_CONFIRMATION;

/** Unscoped scheduled maintenance for AI chat persistence. */
public class JooqAiChatMaintenanceRepository extends JooqBaseRepository
        implements AiChatMaintenanceRepository {

    public JooqAiChatMaintenanceRepository(DSLContext dslContext, ScoreUser requester,
                                           RepositoryFactory repositoryFactory) {
        super(dslContext, Objects.requireNonNull(requester, "requester must not be null"),
                repositoryFactory);
    }

    @Override
    public int deleteExpiredConversations(Instant cutoff) {
        return dslContext().deleteFrom(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.UPDATED_AT.lt(localDateTime(cutoff))
                        .and(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.isNull()))
                .execute();
    }

    @Override
    public int expireMutationConfirmations(Instant now) {
        return dslContext().update(AI_CHAT_MUTATION_CONFIRMATION)
                .set(AI_CHAT_MUTATION_CONFIRMATION.STATUS, "EXPIRED")
                .set(AI_CHAT_MUTATION_CONFIRMATION.EXPIRED_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT)
                .setNull(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_MUTATION_CONFIRMATION.STATUS.in("REQUESTED", "APPROVED")
                        .and(AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT.le(localDateTime(now))))
                .execute();
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
