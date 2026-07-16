package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.Map;

public record AiConversationModelUpdateRequest(String modelName, String reasoningEffort, String runtime,
                                               Map<String, Object> runtimeOptions) {
    public AiConversationModelUpdateRequest(String modelName, String reasoningEffort, String runtime) {
        this(modelName, reasoningEffort, runtime, null);
    }
}
