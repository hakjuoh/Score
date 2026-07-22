package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.time.Instant;
import java.util.List;

public record ChatConversationDetails(String conversationId, String title, String modelName,
                                      String reasoningEffort, Instant updatedAt,
                                      List<ChatHistoryMessage> messages,
                                      List<ChatContextMessage> contextMessages,
                                      AiContextUsageInfo contextUsage,
                                      String permissionMode,
                                      String activeWorkflow) {
    public ChatConversationDetails(String conversationId, String title, String modelName,
                                   String reasoningEffort, Instant updatedAt,
                                   List<ChatHistoryMessage> messages,
                                   List<ChatContextMessage> contextMessages) {
        this(conversationId, title, modelName, reasoningEffort, updatedAt,
                messages, contextMessages, null, "ask", null);
    }

    public ChatConversationDetails {
        permissionMode = "auto".equals(permissionMode) || "full_access".equals(permissionMode)
                ? permissionMode : "ask";
    }
}
