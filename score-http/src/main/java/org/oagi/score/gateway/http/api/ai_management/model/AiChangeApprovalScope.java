package org.oagi.score.gateway.http.api.ai_management.model;

import org.springframework.util.StringUtils;

/** Identifies the root request and optional parallel participant that owns an approval. */
public record AiChangeApprovalScope(
        String rootConversationId,
        String parallelGroupId,
        String participantId,
        String agentId,
        String agentLabel) {

    public AiChangeApprovalScope {
        if (!StringUtils.hasText(rootConversationId)) {
            throw new IllegalArgumentException("An approval scope requires a root conversation.");
        }
    }

    public static AiChangeApprovalScope root(String conversationId) {
        return new AiChangeApprovalScope(conversationId, null, "root", null, "Main assistant");
    }

    public static AiChangeApprovalScope individual(
            String rootConversationId, String participantId,
            String agentId, String agentLabel) {
        return new AiChangeApprovalScope(rootConversationId, null, participantId, agentId, agentLabel);
    }

    public boolean parallel() {
        return StringUtils.hasText(parallelGroupId);
    }
}
