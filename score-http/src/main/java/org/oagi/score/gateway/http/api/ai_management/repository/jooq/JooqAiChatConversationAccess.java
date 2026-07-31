package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationId;
import org.oagi.score.gateway.http.common.model.Id;
import org.springframework.security.access.AccessDeniedException;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;

/** Resolves requester-owned conversation identities for query and command repositories. */
final class JooqAiChatConversationAccess {

    private final DSLContext dslContext;
    private final UserId userId;

    JooqAiChatConversationAccess(DSLContext dslContext, UserId userId) {
        this.dslContext = dslContext;
        this.userId = userId;
    }

    UserId userId() {
        return userId;
    }

    AiChatConversationId ownedId(String conversationId) {
        return ownedId(conversationId, false);
    }

    AiChatConversationId lockOwned(String conversationId) {
        return ownedId(conversationId, true);
    }

    void requireOwned(String conversationId) {
        boolean exists = dslContext.fetchExists(AI_CHAT_CONVERSATION,
                AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId))));
        if (!exists) throw accessDenied();
    }

    private AiChatConversationId ownedId(String conversationId, boolean forUpdate) {
        var query = dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId))));
        ULong result = forUpdate
                ? query.forUpdate().fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                : query.fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        if (result == null) throw accessDenied();
        return new AiChatConversationId(result.toBigInteger());
    }

    ULong valueOf(Id id) {
        return id != null ? ULong.valueOf(id.value()) : null;
    }

    private AccessDeniedException accessDenied() {
        return new AccessDeniedException(
                "AI conversation does not exist or is not owned by the signed-in user.");
    }
}
