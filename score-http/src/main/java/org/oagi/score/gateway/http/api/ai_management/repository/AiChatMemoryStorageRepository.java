package org.oagi.score.gateway.http.api.ai_management.repository;

import org.oagi.score.gateway.http.api.ai_management.model.AiChatMemoryEntry;

import java.util.List;

/** Database access contract for bounded Spring AI model-memory rows. */
public interface AiChatMemoryStorageRepository {

    List<String> findConversationIds();

    List<AiChatMemoryEntry> findByConversationId(String conversationId);

    void saveAll(String conversationId, List<AiChatMemoryEntry> entries);

    void deleteByConversationId(String conversationId);
}
