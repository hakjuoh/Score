package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import org.oagi.score.gateway.http.api.ai_management.file.AiFileDescriptor;
import java.util.List;

public record ChatResponse(String agent, String response, String conversationId,
                           boolean continuationRequired, List<String> progress,
                           List<AiFileDescriptor> files,
                           List<AiChatSocketEvent> events) {
    public ChatResponse {
        progress = progress != null ? List.copyOf(progress) : List.of();
        files = files != null ? List.copyOf(files) : List.of();
        events = events != null ? List.copyOf(events) : List.of();
    }

    public ChatResponse(String agent, String response, String conversationId,
                        boolean continuationRequired, List<String> progress,
                        List<AiChatSocketEvent> events) {
        this(agent, response, conversationId, continuationRequired, progress, List.of(), events);
    }

    public ChatResponse(String agent, String response, String conversationId,
                        boolean continuationRequired, List<String> progress) {
        this(agent, response, conversationId, continuationRequired, progress, List.of(), List.of());
    }

    public ChatResponse withEvents(List<AiChatSocketEvent> events) {
        return new ChatResponse(agent, response, conversationId, continuationRequired,
                progress, files, events);
    }
}
