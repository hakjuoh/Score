package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.Map;

/**
 * Persistence-neutral representation of one Spring AI model-memory message.
 *
 * @param messageType stored Spring AI message type
 * @param content message text
 * @param metadata message and tool metadata
 */
public record AiChatMemoryEntry(
        String messageType,
        String content,
        Map<String, Object> metadata) {
}
