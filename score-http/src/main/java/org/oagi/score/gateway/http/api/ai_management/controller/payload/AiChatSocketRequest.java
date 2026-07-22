package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

public record AiChatSocketRequest(String requestId, String prompt, String agent,
                                  String conversationId, String pageContext,
                                  List<ChatAttachment> attachments,
                                  MutationConfirmation mutationConfirmation,
                                  String modelName,
                                  String reasoningEffort,
                                  String permissionMode,
                                  AiMultiAgentOptions multiAgent,
                                  AiUiRouteManifest routeManifest) {

    public AiChatSocketRequest(String requestId, String prompt, String agent,
                               String conversationId, String pageContext,
                               List<ChatAttachment> attachments,
                               MutationConfirmation mutationConfirmation) {
        this(requestId, prompt, agent, conversationId, pageContext, attachments,
                mutationConfirmation, null, null, null, null, null);
    }

    public ChatRequest toChatRequest() {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext,
                attachments, mutationConfirmation, modelName, reasoningEffort,
                permissionMode, multiAgent, null, routeManifest);
    }
}
