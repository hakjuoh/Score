package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

public record AiChatSocketRequest(String requestId, String prompt, String agent,
                                  String conversationId, String pageContext,
                                  List<ChatAttachment> attachments,
                                  ChangeConfirmation changeConfirmation,
                                  String modelName,
                                  String reasoningEffort,
                                  String permissionMode,
                                  AiMultiAgentOptions multiAgent,
                                  AiUiRouteManifest routeManifest) {

    public AiChatSocketRequest(String requestId, String prompt, String agent,
                               String conversationId, String pageContext,
                               List<ChatAttachment> attachments,
                               ChangeConfirmation changeConfirmation) {
        this(requestId, prompt, agent, conversationId, pageContext, attachments,
                changeConfirmation, null, null, null, null, null);
    }

    public ChatRequest toChatRequest() {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext,
                attachments, changeConfirmation, modelName, reasoningEffort,
                permissionMode, multiAgent, null, routeManifest);
    }
}
