package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChatConversationDetails(String conversationId, String title, String modelName,
                                      String reasoningEffort, String runtime,
                                      Map<String, Object> runtimeOptions, Instant updatedAt,
                                      List<ChatHistoryMessage> messages,
                                      List<ChatContextMessage> contextMessages,
                                      AiContextUsageInfo contextUsage) {
    public ChatConversationDetails(String conversationId, String title, String modelName,
                                   String reasoningEffort, String runtime, Instant updatedAt,
                                   List<ChatHistoryMessage> messages,
                                   List<ChatContextMessage> contextMessages) {
        this(conversationId, title, modelName, reasoningEffort, runtime, Map.of(), updatedAt,
                messages, contextMessages, null);
    }

    public ChatConversationDetails(String conversationId, String title, String modelName,
                                   String reasoningEffort, String runtime,
                                   Map<String, Object> runtimeOptions, Instant updatedAt,
                                   List<ChatHistoryMessage> messages,
                                   List<ChatContextMessage> contextMessages) {
        this(conversationId, title, modelName, reasoningEffort, runtime, runtimeOptions, updatedAt,
                messages, contextMessages, null);
    }

    public ChatConversationDetails {
        runtimeOptions = runtimeOptions != null ? Map.copyOf(runtimeOptions) : Map.of();
    }
}
