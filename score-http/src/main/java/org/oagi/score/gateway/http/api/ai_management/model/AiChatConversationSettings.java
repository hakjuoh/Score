package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.Map;

/**
 * Immutable snapshot of the model and runtime settings applied to an AI chat conversation.
 *
 * @param modelName model identifier selected for the conversation
 * @param reasoningEffort provider-specific reasoning effort
 * @param runtime agent runtime identifier
 * @param runtimeOptions runtime-specific option values
 */
public record AiChatConversationSettings(
        String modelName,
        String reasoningEffort,
        String runtime,
        Map<String, Object> runtimeOptions) {

    /**
     * Creates a settings snapshot without runtime-specific options.
     */
    public AiChatConversationSettings(String modelName, String reasoningEffort, String runtime) {
        this(modelName, reasoningEffort, runtime, Map.of());
    }

    public AiChatConversationSettings {
        runtimeOptions = runtimeOptions != null ? Map.copyOf(runtimeOptions) : Map.of();
    }
}
