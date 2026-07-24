package org.oagi.score.gateway.http.configuration.ai;

import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;
import reactor.core.publisher.SynchronousSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps complete and streaming Responses API output to Spring AI responses. */
final class OpenAiResponsesResponseMapper {

    ChatResponse complete(Response response) {
        throwIfFailed(response);
        CollectedOutput output = collect(response.output());
        return chatResponse(output.text(), output.toolCalls(), output.reasoningItems(),
                responseMetadata(response), finishReason(response, output.toolCalls()));
    }

    StreamState streamState() {
        return new StreamState();
    }

    final class StreamState {

        private final Map<String, Map<String, Object>> reasoningItems = new LinkedHashMap<>();

        void accept(ResponseStreamEvent event, SynchronousSink<ChatResponse> sink) {
            if (event.isError()) {
                sink.error(new IllegalStateException(
                        "OpenAI Responses API error: " + event.asError().message()));
                return;
            }
            if (event.isFailed()) {
                sink.error(failure(event.asFailed().response()));
                return;
            }
            if (event.isIncomplete()) {
                sink.error(incomplete(event.asIncomplete().response()));
                return;
            }
            if (event.isOutputItemDone()) {
                ResponseOutputItem item = event.asOutputItemDone().item();
                if (item.isReasoning()) {
                    remember(item.asReasoning());
                } else if (item.isFunctionCall()) {
                    var call = item.asFunctionCall();
                    sink.next(chatResponse("", List.of(new AssistantMessage.ToolCall(
                                    call.callId(), "function", call.name(), call.arguments())),
                            reasoningMetadata(), ChatResponseMetadata.builder().build(),
                            "tool_calls"));
                }
                return;
            }
            if (event.isOutputTextDelta()) {
                sink.next(chatResponse(event.asOutputTextDelta().delta(), List.of(),
                        reasoningMetadata(), ChatResponseMetadata.builder().build(), ""));
                return;
            }
            if (event.isRefusalDelta()) {
                sink.next(chatResponse(event.asRefusalDelta().delta(), List.of(),
                        reasoningMetadata(), ChatResponseMetadata.builder().build(), ""));
                return;
            }
            if (event.isCompleted()) {
                Response response = event.asCompleted().response();
                CollectedOutput completed = collect(response.output());
                completed.reasoningItems().forEach(record ->
                        reasoningItems.put(java.util.Objects.toString(record.get("id")), record));
                sink.next(chatResponse("", List.of(), reasoningMetadata(),
                        responseMetadata(response),
                        finishReason(response, completed.toolCalls())));
            }
        }

        private void remember(ResponseReasoningItem item) {
            reasoningRecord(item).ifPresent(record ->
                    reasoningItems.put(java.util.Objects.toString(record.get("id")), record));
        }

        private List<Map<String, Object>> reasoningMetadata() {
            return List.copyOf(reasoningItems.values());
        }
    }

    private CollectedOutput collect(List<ResponseOutputItem> items) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        List<Map<String, Object>> reasoning = new ArrayList<>();
        for (ResponseOutputItem item : items) {
            if (item.isMessage()) {
                item.asMessage().content().forEach(content -> {
                    if (content.isOutputText()) {
                        text.append(content.asOutputText().text());
                    } else if (content.isRefusal()) {
                        text.append(content.asRefusal().refusal());
                    }
                });
            } else if (item.isFunctionCall()) {
                var call = item.asFunctionCall();
                toolCalls.add(new AssistantMessage.ToolCall(
                        call.callId(), "function", call.name(), call.arguments()));
            } else if (item.isReasoning()) {
                reasoningRecord(item.asReasoning()).ifPresent(reasoning::add);
            }
        }
        return new CollectedOutput(text.toString(), toolCalls, reasoning);
    }

    private java.util.Optional<Map<String, Object>> reasoningRecord(
            ResponseReasoningItem item) {
        if (item.encryptedContent().isEmpty()) return java.util.Optional.empty();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", item.id());
        record.put("encryptedContent", item.encryptedContent().orElseThrow());
        record.put("summary", item.summary().stream()
                .map(ResponseReasoningItem.Summary::text).toList());
        item.status().ifPresent(status -> record.put("status", status.asString()));
        return java.util.Optional.of(Map.copyOf(record));
    }

    private ChatResponse chatResponse(String text,
                                      List<AssistantMessage.ToolCall> toolCalls,
                                      List<Map<String, Object>> reasoningItems,
                                      ChatResponseMetadata metadata,
                                      String finishReason) {
        Map<String, Object> properties = reasoningItems.isEmpty() ? Map.of()
                : Map.of(OpenAiResponsesRequestMapper.REASONING_ITEMS_METADATA_KEY,
                reasoningItems);
        AssistantMessage message = AssistantMessage.builder()
                .content(text)
                .properties(properties)
                .toolCalls(toolCalls)
                .build();
        ChatGenerationMetadata generationMetadata = StringUtils.hasText(finishReason)
                ? ChatGenerationMetadata.builder().finishReason(finishReason).build()
                : ChatGenerationMetadata.NULL;
        return new ChatResponse(List.of(new Generation(message, generationMetadata)), metadata);
    }

    private ChatResponseMetadata responseMetadata(Response response) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(response.id())
                .model(modelName(response.model()));
        response.usage().ifPresent(usage -> metadata.usage(usage(usage)));
        return metadata.build();
    }

    private String modelName(ResponsesModel model) {
        if (model.isChat()) return model.asChat().asString();
        if (model.isOnly()) return model.asOnly().asString();
        if (model.isString()) return model.asString();
        throw new IllegalStateException(
                "OpenAI Responses API returned an unsupported model value: " + model);
    }

    private DefaultUsage usage(ResponseUsage usage) {
        return new DefaultUsage(safeInt(usage.inputTokens()),
                safeInt(usage.outputTokens()), safeInt(usage.totalTokens()), usage);
    }

    private int safeInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private String finishReason(Response response,
                                List<AssistantMessage.ToolCall> toolCalls) {
        if (!toolCalls.isEmpty()) return "tool_calls";
        return response.status().map(ResponseStatus::asString).orElse("completed");
    }

    private void throwIfFailed(Response response) {
        response.error().ifPresent(error -> {
            throw new IllegalStateException(
                    "OpenAI Responses API error: " + error.message());
        });
        if (response.status().filter(ResponseStatus.FAILED::equals).isPresent()) {
            throw failure(response);
        }
        if (response.status().filter(ResponseStatus.INCOMPLETE::equals).isPresent()) {
            throw incomplete(response);
        }
    }

    private IllegalStateException failure(Response response) {
        String message = response.error().map(error -> error.message())
                .orElse("The model response failed.");
        return new IllegalStateException("OpenAI Responses API error: " + message);
    }

    private IllegalStateException incomplete(Response response) {
        String reason = response.incompleteDetails()
                .flatMap(Response.IncompleteDetails::reason)
                .map(value -> value.asString())
                .orElse("unknown");
        return new IllegalStateException(
                "OpenAI Responses API returned an incomplete response: " + reason);
    }

    private record CollectedOutput(String text,
                                   List<AssistantMessage.ToolCall> toolCalls,
                                   List<Map<String, Object>> reasoningItems) {}
}
