package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One immutable, generation-fenced view supplied to root Agent/Workflow execution. */
public record ConversationSnapshot(String conversationId, long generation,
                                   ConversationSettings settings,
                                   List<AiMessage> history,
                                   List<AiMessage> transcript,
                                   Map<String, Object> memory) {
    public ConversationSnapshot {
        conversationId = Objects.requireNonNull(conversationId).strip();
        if (conversationId.isEmpty() || generation < 0) {
            throw new IllegalArgumentException("Conversation snapshot identity is invalid.");
        }
        Objects.requireNonNull(settings, "settings");
        history = history != null ? List.copyOf(history) : List.of();
        transcript = transcript != null ? List.copyOf(transcript) : List.of();
        memory = memory != null ? Map.copyOf(memory) : Map.of();
    }

    public record ConversationSettings(String modelId, String reasoningEffort,
                                       String workflowId, String agentId) {
        public ConversationSettings {
            modelId = Objects.requireNonNull(modelId, "modelId").strip();
            agentId = Objects.requireNonNullElse(agentId, "connectcenter-assistant");
        }
    }
}
