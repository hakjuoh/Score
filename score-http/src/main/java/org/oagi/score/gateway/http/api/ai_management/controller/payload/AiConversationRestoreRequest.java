package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record AiConversationRestoreRequest(String requestId, String conversationId,
                                           String restoreToken, Long restoreSequence) {}
