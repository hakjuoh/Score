package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.Map;

public record ChatHistoryMessage(int index, String role, String content,
                                 String requestId, String turnId, String groupId,
                                 String toolCallId, Long toolCallSequence,
                                 String subtype, String visibility,
                                 Map<String, Object> metadata) {

    public ChatHistoryMessage {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
