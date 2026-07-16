package org.oagi.score.gateway.http.api.ai_management.service;

import java.util.Map;

public record AiExecutionEvent(String type, String subtype, String content,
                               String toolCallId, String toolName,
                               Long toolCallSequence, Map<String, Object> metadata) {

    public AiExecutionEvent {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static AiExecutionEvent progress(String content) {
        return new AiExecutionEvent("progress", "progress", content, null, null, null,
                Map.of("inProgress", true));
    }

    public static AiExecutionEvent contentDelta(String content) {
        return new AiExecutionEvent("assistant_update", "content_delta", content,
                null, null, null, Map.of("inProgress", true));
    }

    public static AiExecutionEvent detail(String subtype, String content, Map<String, Object> metadata) {
        return new AiExecutionEvent("detail", subtype, content, null, null, null, metadata);
    }

    public static AiExecutionEvent tool(String subtype, String content, String toolCallId,
                                        String toolName, long toolCallSequence) {
        return tool(subtype, content, toolCallId, toolName, toolCallSequence, Map.of());
    }

    public static AiExecutionEvent tool(String subtype, String content, String toolCallId,
                                        String toolName, long toolCallSequence,
                                        Map<String, Object> metadata) {
        return new AiExecutionEvent("tool_call", subtype, content, toolCallId, toolName,
                toolCallSequence, metadata);
    }
}
