package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.Map;

public record AiConversationModelResponse(String conversationId, String modelName,
                                          String reasoningEffort, String runtime,
                                          Map<String, Object> runtimeOptions,
                                          boolean contextCompacted,
                                          AiContextUsageInfo contextUsage) {
    public AiConversationModelResponse(String conversationId, String modelName,
                                       String reasoningEffort, String runtime) {
        this(conversationId, modelName, reasoningEffort, runtime, Map.of(), false, null);
    }

    public AiConversationModelResponse(String conversationId, String modelName,
                                       String reasoningEffort, String runtime,
                                       Map<String, Object> runtimeOptions) {
        this(conversationId, modelName, reasoningEffort, runtime, runtimeOptions, false, null);
    }

    public AiConversationModelResponse {
        runtimeOptions = runtimeOptions != null ? Map.copyOf(runtimeOptions) : Map.of();
    }
}
