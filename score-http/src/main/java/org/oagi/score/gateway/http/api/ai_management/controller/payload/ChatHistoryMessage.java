package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import org.oagi.score.gateway.http.api.ai_management.artifact.AiArtifactDescriptor;
import java.util.List;
import java.util.Map;

public record ChatHistoryMessage(int index, String role, String content,
                                 String requestId, String turnId, String groupId,
                                 String toolCallId, Long toolCallSequence,
                                 String subtype, String visibility,
                                 List<AiArtifactDescriptor> artifacts,
                                 Map<String, Object> metadata) {

    public ChatHistoryMessage {
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public ChatHistoryMessage(int index, String role, String content,
                              String requestId, String turnId, String groupId,
                              String toolCallId, Long toolCallSequence,
                              String subtype, String visibility,
                              Map<String, Object> metadata) {
        this(index, role, content, requestId, turnId, groupId, toolCallId,
                toolCallSequence, subtype, visibility, List.of(), metadata);
    }
}
