package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
        String runtime,
        Map<String, Object> runtimeOptions,
        String permissionMode) {

    public ChatRequest(String prompt, String requestId, String agent, String conversationId,
                       String pageContext, List<ChatAttachment> attachments,
                       MutationConfirmation mutationConfirmation) {
        this(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, null, null, null, null, null);
    }

    public ChatRequest(String prompt, String requestId, String agent, String conversationId,
                       String pageContext, List<ChatAttachment> attachments,
                       MutationConfirmation mutationConfirmation, String modelName,
                       String reasoningEffort, String runtime) {
        this(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, runtime, null, null);
    }

    public ChatRequest(String prompt, String requestId, String agent, String conversationId,
                       String pageContext, List<ChatAttachment> attachments,
                       MutationConfirmation mutationConfirmation, String modelName,
                       String reasoningEffort, String runtime, Map<String, Object> runtimeOptions) {
        this(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, runtime, runtimeOptions, null);
    }

    public ChatRequest {
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        runtimeOptions = runtimeOptions != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(runtimeOptions)) : null;
    }

    public ChatRequest withConversation(String conversationId, String modelName,
                                        String reasoningEffort, String runtime,
                                        Map<String, Object> runtimeOptions) {
        return new ChatRequest(prompt, requestId, agent, conversationId, pageContext, attachments,
                mutationConfirmation, modelName, reasoningEffort, runtime, runtimeOptions, permissionMode);
    }
}
