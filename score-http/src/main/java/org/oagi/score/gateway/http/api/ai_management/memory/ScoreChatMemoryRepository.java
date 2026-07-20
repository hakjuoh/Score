package org.oagi.score.gateway.http.api.ai_management.memory;

import org.oagi.score.gateway.http.api.ai_management.model.AiChatMemoryEntry;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMemoryStorageRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Spring AI chat-memory adapter backed by connectCenter persistence repositories.
 * This adapter translates Spring AI messages but performs no SQL directly.
 */
public class ScoreChatMemoryRepository implements ChatMemoryRepository {

    private final AiChatMemoryStorageRepository storageRepository;
    private final TransactionOperations transactions;

    ScoreChatMemoryRepository(AiChatMemoryStorageRepository storageRepository,
                              TransactionOperations transactions) {
        this.storageRepository = Objects.requireNonNull(storageRepository,
                "storageRepository must not be null");
        this.transactions = Objects.requireNonNull(transactions,
                "transactions must not be null");
    }

    ScoreChatMemoryRepository(AiChatMemoryStorageRepository storageRepository) {
        this(storageRepository, TransactionOperations.withoutTransaction());
    }

    @Override
    public List<String> findConversationIds() {
        return transactions.execute(status -> storageRepository.findConversationIds());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return transactions.execute(status -> storageRepository.findByConversationId(conversationId).stream()
                .map(this::memoryMessage).toList());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must not contain null elements");
        }
        transactions.executeWithoutResult(status -> storageRepository.saveAll(conversationId,
                messages.stream().map(this::memoryEntry).toList()));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        transactions.executeWithoutResult(
                status -> storageRepository.deleteByConversationId(conversationId));
    }

    private AiChatMemoryEntry memoryEntry(Message message) {
        Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata());
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            metadata.put("score_tool_calls", assistant.getToolCalls());
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            metadata.put("score_tool_responses", toolResponse.getResponses());
        }
        return new AiChatMemoryEntry(message.getMessageType().name(),
                Objects.requireNonNullElse(message.getText(), ""), Map.copyOf(metadata));
    }

    private Message memoryMessage(AiChatMemoryEntry entry) {
        MessageType type = storedMessageType(entry.messageType());
        Map<String, Object> metadata = entry.metadata() != null ? entry.metadata() : Map.of();
        return switch (type) {
            case USER -> UserMessage.builder().text(entry.content()).metadata(metadata).build();
            case ASSISTANT -> AssistantMessage.builder().content(entry.content()).properties(metadata)
                    .toolCalls(assistantToolCalls(metadata)).build();
            case SYSTEM -> SystemMessage.builder().text(entry.content()).metadata(metadata).build();
            case TOOL -> ToolResponseMessage.builder().responses(toolResponses(metadata))
                    .metadata(metadata).build();
        };
    }

    static MessageType storedMessageType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "Stored AI chat memory message_type is missing and has no safe default.");
        }
        try {
            return MessageType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Stored AI chat memory message_type is unsupported: " + value, failure);
        }
    }

    private List<ToolResponseMessage.ToolResponse> toolResponses(Map<String, Object> metadata) {
        Object raw = metadata.get("score_tool_responses");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                responses.add(new ToolResponseMessage.ToolResponse(
                        Objects.toString(map.get("id"), ""),
                        Objects.toString(map.get("name"), ""),
                        Objects.toString(map.get("responseData"), "")));
            }
        }
        return responses;
    }

    private List<AssistantMessage.ToolCall> assistantToolCalls(Map<String, Object> metadata) {
        Object raw = metadata.get("score_tool_calls");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                calls.add(new AssistantMessage.ToolCall(
                        Objects.toString(map.get("id"), ""),
                        Objects.toString(map.get("type"), "function"),
                        Objects.toString(map.get("name"), ""),
                        Objects.toString(map.get("arguments"), "{}")));
            }
        }
        return calls;
    }
}
