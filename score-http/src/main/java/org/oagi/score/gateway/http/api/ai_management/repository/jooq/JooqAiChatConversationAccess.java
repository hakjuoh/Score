package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.springframework.security.access.AccessDeniedException;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;

/** Resolves requester-owned conversation identities for query and command repositories. */
final class JooqAiChatConversationAccess {

    private final DSLContext dslContext;
    private final ULong userId;

    JooqAiChatConversationAccess(DSLContext dslContext, ULong userId) {
        this.dslContext = dslContext;
        this.userId = userId;
    }

    ULong userId() {
        return userId;
    }

    ULong ownedId(String conversationId) {
        return ownedId(conversationId, false);
    }

    ULong lockOwned(String conversationId) {
        return ownedId(conversationId, true);
    }

    void requireOwned(String conversationId) {
        boolean exists = dslContext.fetchExists(AI_CHAT_CONVERSATION,
                AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId)));
        if (!exists) throw accessDenied();
    }

    private ULong ownedId(String conversationId, boolean forUpdate) {
        var query = dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId)));
        ULong result = forUpdate
                ? query.forUpdate().fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                : query.fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        if (result == null) throw accessDenied();
        return result;
    }

    private AccessDeniedException accessDenied() {
        return new AccessDeniedException(
                "AI conversation does not exist or is not owned by the signed-in user.");
    }
}
