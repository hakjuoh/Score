package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;
import java.util.Map;

public record AiChatSocketRequest(String requestId, String prompt, String agent,
                                  String conversationId, String pageContext,
                                  List<ChatAttachment> attachments,
                                  MutationConfirmation mutationConfirmation,
                                  String modelName,
                                  String reasoningEffort,
                                  String runtime,
                                  Map<String, Object> runtimeOptions,
                                  String permissionMode,
                                  AiMultiAgentOptions multiAgent) {

    public AiChatSocketRequest(String requestId, String prompt, String agent,
                               String conversationId, String pageContext,
                               List<ChatAttachment> attachments,
                               MutationConfirmation mutationConfirmation) {
        this(requestId, prompt, agent, conversationId, pageContext, attachments,
                mutationConfirmation, null, null, null, null, null, null);
    }

    public AiChatSocketRequest(String requestId, String prompt, String agent,
                               String conversationId, String pageContext,
                               List<ChatAttachment> attachments,
                               MutationConfirmation mutationConfirmation,
                               String modelName, String reasoningEffort, String runtime) {
        this(requestId, prompt, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, runtime, null, null, null);
    }

    public AiChatSocketRequest(String requestId, String prompt, String agent,
                               String conversationId, String pageContext,
                               List<ChatAttachment> attachments,
                               MutationConfirmation mutationConfirmation,
                               String modelName, String reasoningEffort, String runtime,
                               Map<String, Object> runtimeOptions) {
        this(requestId, prompt, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, runtime, runtimeOptions, null, null);
    }

    public AiChatSocketRequest(String requestId, String prompt, String agent,
                               String conversationId, String pageContext,
                               List<ChatAttachment> attachments,
                               MutationConfirmation mutationConfirmation,
                               String modelName, String reasoningEffort, String runtime,
                               Map<String, Object> runtimeOptions, String permissionMode) {
        this(requestId, prompt, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, runtime, runtimeOptions,
                permissionMode, null);
    }

    public ChatRequest toChatRequest() {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext,
                attachments, mutationConfirmation, modelName, reasoningEffort, runtime,
                runtimeOptions, permissionMode, multiAgent);
    }
}
