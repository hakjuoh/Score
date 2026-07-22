package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

public record ChatRequest(
        String prompt,
        String requestId,
        String agent,
        String conversationId,
        String pageContext,
        List<ChatAttachment> attachments,
        MutationConfirmation mutationConfirmation,
        String modelName,
        String reasoningEffort,
        String permissionMode,
        AiMultiAgentOptions multiAgent,
        String activeWorkflow,
        AiUiRouteManifest routeManifest) {

    public ChatRequest(String prompt, String requestId, String agent, String conversationId,
                       String pageContext, List<ChatAttachment> attachments,
                       MutationConfirmation mutationConfirmation) {
        this(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, null, null, null, null, null, null);
    }

    public ChatRequest(String prompt, String requestId, String agent, String conversationId,
                       String pageContext, List<ChatAttachment> attachments,
                       MutationConfirmation mutationConfirmation, String modelName,
                       String reasoningEffort, String permissionMode) {
        this(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, permissionMode, null, null, null);
    }

    public ChatRequest {
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        multiAgent = multiAgent != null ? multiAgent : AiMultiAgentOptions.single();
    }

    public ChatRequest withConversation(String conversationId, String modelName,
                                        String reasoningEffort) {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, permissionMode,
                multiAgent, activeWorkflow, routeManifest);
    }

    public ChatRequest withMultiAgent(AiMultiAgentOptions options) {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, permissionMode,
                options, activeWorkflow, routeManifest);
    }

    public ChatRequest withActiveWorkflow(String workflow) {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, permissionMode,
                multiAgent, workflow, routeManifest);
    }

    public ChatRequest withConversationId(String value) {
        return new ChatRequest(prompt, requestId, agent, value, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, permissionMode,
                multiAgent, activeWorkflow, routeManifest);
    }
}
