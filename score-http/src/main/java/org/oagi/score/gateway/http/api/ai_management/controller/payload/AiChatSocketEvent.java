package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileDescriptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AiChatSocketEvent(
        String requestId,
        String conversationId,
        String type,
        String message,
        String agent,
        String response,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean continuationRequired,
        List<String> progress,
        String resource,
        String action,
        String targetPath,
        List<String> ids,
        Integer index,
        String turnId,
        Long sequence,
        String subtype,
        String visibility,
        String content,
        String groupId,
        String toolCallId,
        List<AiFileDescriptor> files,
        Map<String, Object> metadata) {

    public AiChatSocketEvent {
        progress = progress != null ? List.copyOf(progress) : List.of();
        ids = ids != null ? List.copyOf(ids) : List.of();
        files = files != null ? List.copyOf(files) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public AiChatSocketEvent(String requestId, String conversationId, String type, String message,
                             String agent, String response, boolean continuationRequired,
                             List<String> progress, String resource, String action, String targetPath,
                             List<String> ids, Integer index, String turnId, Long sequence,
                             String subtype, String visibility, String content, String groupId,
                             String toolCallId, Map<String, Object> metadata) {
        this(requestId, conversationId, type, message, agent, response, continuationRequired,
                progress, resource, action, targetPath, ids, index, turnId, sequence, subtype,
                visibility, content, groupId, toolCallId, List.of(), metadata);
    }

    public static AiChatSocketEvent accepted(String requestId, String conversationId,
                                             long generation, Instant deadline) {
        return system(requestId, conversationId, null, "accepted", "Request received.",
                Map.of("generation", generation, "deadline", deadline.toString()));
    }

    public static AiChatSocketEvent progress(String requestId, String conversationId,
                                             long sequence, String content) {
        return system(requestId, conversationId, sequence, "progress", content,
                Map.of("inProgress", true));
    }

    public static AiChatSocketEvent assistantUpdate(String requestId, String conversationId,
                                                    long sequence, String content) {
        return new AiChatSocketEvent(requestId, conversationId, "assistant_update", "Streaming response.",
                null, null, false, List.of(), null, null, null, List.of(), null,
                requestId, sequence, "content_delta", "visible", content,
                null, null, Map.of("inProgress", true));
    }

    public static AiChatSocketEvent finalResponse(String requestId, ChatResponse response) {
        return new AiChatSocketEvent(requestId, response.conversationId(), "assistant_final", "Completed.",
                response.agent(), response.response(), response.continuationRequired(), response.progress(),
                null, null, null, List.of(), null, requestId, null, null, "visible",
                response.response(), null, null, response.files(),
                Map.of("continuationRequired", response.continuationRequired()));
    }

    public static AiChatSocketEvent error(String requestId, String conversationId, String message) {
        return system(requestId, conversationId, null, "error", message, Map.of());
    }

    public static AiChatSocketEvent cancelled(String requestId, String conversationId, long generation,
                                              String cancellationRequestId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generation", generation);
        metadata.put("status", "CANCELLED");
        metadata.put("terminal", true);
        metadata.put("cancellationRequestId", cancellationRequestId);
        return system(requestId, conversationId, 1L, "cancelled", "Request cancelled.", metadata);
    }

    public static AiChatSocketEvent cancellationAcknowledged(String requestId, String conversationId,
                                                              long generation, long sequence,
                                                              String cancellationRequestId) {
        return system(requestId, conversationId, sequence, "cancellation_acknowledged",
                "Cancellation acknowledged; waiting for the active operation to stop.", Map.of(
                        "generation", generation,
                        "status", "CANCELLING",
                        "terminal", false,
                        "lifecycleEventSequence", sequence,
                        "cancellationRequestId", cancellationRequestId));
    }

    public static AiChatSocketEvent terminalError(String requestId, String conversationId,
                                                  long generation, String status, String message) {
        return system(requestId, conversationId, 1L, "request_error", message, Map.of(
                "generation", generation, "status", status, "terminal", true,
                "recoverable", false, "retryable", false));
    }

    public static AiChatSocketEvent reconciliationRequired(String requestId, String conversationId,
                                                           long generation) {
        return system(requestId, conversationId, 1L, "reconciliation_required",
                "A data-changing operation may have completed while cancellation was in progress.", Map.of(
                        "generation", generation,
                        "status", "UNKNOWN_RECONCILIATION_REQUIRED",
                        "terminal", true, "recoverable", false, "retryable", false,
                        "reconciliationRequired", true));
    }

    public static AiChatSocketEvent historyStart(String requestId, String conversationId, String title,
                                                 Map<String, Object> metadata) {
        return event(requestId, conversationId, "HISTORY_START", title, null, null, null, metadata);
    }

    public static AiChatSocketEvent historyMessage(String requestId, String conversationId,
                                                   ChatHistoryMessage message, Map<String, Object> metadata) {
        Map<String, Object> eventMetadata = new LinkedHashMap<>(metadata);
        eventMetadata.putAll(message.metadata());
        return new AiChatSocketEvent(requestId, conversationId, "HISTORY_MESSAGE", message.role(), null,
                message.content(), false, List.of(), null, null, null, List.of(), message.index(),
                message.turnId(), null, message.subtype(), message.visibility(), message.content(),
                message.groupId(), message.toolCallId(), message.files(), eventMetadata);
    }

    public static AiChatSocketEvent detail(String requestId, String conversationId, long sequence,
                                           String subtype, String content, Map<String, Object> metadata) {
        return new AiChatSocketEvent(requestId, conversationId, "system", content, null, null,
                false, List.of(), null, null, null, List.of(), null, requestId, sequence,
                subtype, "debug", content, null, null, metadata);
    }

    public static AiChatSocketEvent changeConfirmationRequired(
            String requestId, String conversationId, long sequence, String content,
            Map<String, Object> metadata) {
        return new AiChatSocketEvent(requestId, conversationId, "system", content, null, null,
                false, List.of(), null, null, null, List.of(), null, requestId, sequence,
                "change_confirmation_required", "visible", content, null, null, metadata);
    }

    public static AiChatSocketEvent elicitationRequired(
            String requestId, String conversationId, long sequence, String content,
            Map<String, Object> metadata) {
        return new AiChatSocketEvent(requestId, conversationId, "system", content, null, null,
                false, List.of(), null, null, null, List.of(), null, requestId, sequence,
                "elicitation_required", "visible", content, null, null, metadata);
    }

    public static AiChatSocketEvent toolCall(String requestId, String conversationId, long sequence,
                                             String subtype, String content, String toolCallId,
                                             String toolName, long toolCallSequence,
                                             Map<String, Object> additionalMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                additionalMetadata != null ? additionalMetadata : Map.of());
        metadata.put("toolName", toolName);
        metadata.put("toolCallSeq", toolCallSequence);
        metadata.put("statusMessage", content);
        if ("failed".equals(subtype)) {
            metadata.put("terminal", false);
            metadata.put("recoverable", true);
            metadata.put("retryable", false);
            metadata.put("changeSafe", true);
        }
        return new AiChatSocketEvent(requestId, conversationId, "tool_call", content, null, null,
                false, List.of(), null, null, null, List.of(), null, requestId, sequence,
                subtype, "debug", content, requestId, toolCallId, metadata);
    }

    public static AiChatSocketEvent historyFinal(String requestId, String conversationId,
                                                 Map<String, Object> metadata) {
        return event(requestId, conversationId, "HISTORY_FINAL", "Restored.", null, null, null, metadata);
    }

    public static AiChatSocketEvent system(String requestId, String conversationId, Long sequence,
                                           String subtype, String content, Map<String, Object> metadata) {
        return new AiChatSocketEvent(requestId, conversationId, "system", content, null, null,
                false, List.of(), null, null, null, List.of(), null, requestId, sequence,
                subtype, "visible", content, null, null, metadata);
    }

    private static AiChatSocketEvent event(String requestId, String conversationId, String type,
                                           String message, String response, Integer index,
                                           String subtype, Map<String, Object> metadata) {
        return new AiChatSocketEvent(requestId, conversationId, type, message, null, response,
                false, List.of(), null, null, null, List.of(), index, null, null,
                subtype, "visible", response != null ? response : message, null, null, metadata);
    }
}
