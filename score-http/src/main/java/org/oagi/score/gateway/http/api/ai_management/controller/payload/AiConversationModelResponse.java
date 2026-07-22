package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record AiConversationModelResponse(String conversationId, String modelName,
                                          String reasoningEffort, boolean contextCompacted,
                                          AiContextUsageInfo contextUsage) {
    public AiConversationModelResponse(String conversationId, String modelName,
                                       String reasoningEffort) {
        this(conversationId, modelName, reasoningEffort, false, null);
    }
}
