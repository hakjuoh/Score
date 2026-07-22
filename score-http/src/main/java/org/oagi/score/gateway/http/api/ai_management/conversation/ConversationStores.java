package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Application-owned durable ports. Persistence adapters may implement several together. */
public final class ConversationStores {

    private ConversationStores() { }

    public interface State {
        ConversationSnapshot.ConversationSettings settings(String conversationId);
        void saveSettings(String conversationId, ConversationSnapshot.ConversationSettings settings);
    }

    public interface History {
        List<AiMessage> load(String conversationId);
        void append(String conversationId, List<AiMessage> messages);
        void replace(String conversationId, List<AiMessage> messages);
    }

    public interface Transcript {
        List<AiMessage> load(String conversationId);
        void append(String conversationId, List<AiMessage> messages);
    }

    public interface Memory {
        Map<String, Object> load(String conversationId);
        void replace(String conversationId, Map<String, Object> memory);
    }

    public interface ProviderHandle {
        Optional<String> load(String conversationId, String modelId);
        void save(String conversationId, String modelId, String handle);
        void discard(String conversationId, String modelId);
    }
}
