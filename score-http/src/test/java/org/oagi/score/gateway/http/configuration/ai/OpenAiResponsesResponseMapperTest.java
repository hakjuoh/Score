package org.oagi.score.gateway.http.configuration.ai;

import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponsesResponseMapperTest {

    @Test
    void mapsTextToolCallsUsageAndEncryptedReasoning() {
        ResponseReasoningItem reasoning = reasoning();
        ResponseFunctionToolCall toolCall = toolCall();
        Response response = response(List.of(
                ResponseOutputItem.ofReasoning(reasoning),
                ResponseOutputItem.ofMessage(message()),
                ResponseOutputItem.ofFunctionCall(toolCall)));

        var mapped = new OpenAiResponsesResponseMapper().complete(response);

        assertThat(mapped.getResult().getOutput().getText()).isEqualTo("Checking.");
        assertThat(mapped.getResult().getOutput().getToolCalls())
                .containsExactly(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        "call_1", "function", "lookup", "{\"id\":\"42\"}"));
        assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("tool_calls");
        assertThat(mapped.getMetadata().getId()).isEqualTo("resp_1");
        assertThat(mapped.getMetadata().getModel()).isEqualTo("gpt-5.6-terra");
        assertThat(mapped.getMetadata().getUsage().getPromptTokens()).isEqualTo(12);
        assertThat(mapped.getMetadata().getUsage().getCompletionTokens()).isEqualTo(5);
        assertThat(mapped.getResult().getOutput().getMetadata())
                .containsKey(OpenAiResponsesRequestMapper.REASONING_ITEMS_METADATA_KEY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasoningMetadata = (List<Map<String, Object>>)
                mapped.getResult().getOutput().getMetadata()
                        .get(OpenAiResponsesRequestMapper.REASONING_ITEMS_METADATA_KEY);
        assertThat(reasoningMetadata).singleElement().satisfies(item -> {
            assertThat(item.get("id")).isEqualTo("rs_1");
            assertThat(item.get("encryptedContent")).isEqualTo("encrypted");
        });
    }

    @Test
    void streamsTextReasoningToolCallsAndCompletionMetadata() {
        ResponseReasoningItem reasoning = reasoning();
        ResponseFunctionToolCall toolCall = toolCall();
        Response completed = response(List.of(
                ResponseOutputItem.ofReasoning(reasoning),
                ResponseOutputItem.ofFunctionCall(toolCall)));
        List<ResponseStreamEvent> events = List.of(
                ResponseStreamEvent.ofOutputTextDelta(ResponseTextDeltaEvent.builder()
                        .contentIndex(0)
                        .delta("Checking.")
                        .itemId("msg_1")
                        .logprobs(List.of())
                        .outputIndex(0)
                        .sequenceNumber(1)
                        .build()),
                ResponseStreamEvent.ofOutputItemDone(ResponseOutputItemDoneEvent.builder()
                        .item(reasoning)
                        .outputIndex(1)
                        .sequenceNumber(2)
                        .build()),
                ResponseStreamEvent.ofOutputItemDone(ResponseOutputItemDoneEvent.builder()
                        .item(toolCall)
                        .outputIndex(2)
                        .sequenceNumber(3)
                        .build()),
                ResponseStreamEvent.ofCompleted(ResponseCompletedEvent.builder()
                        .response(completed)
                        .sequenceNumber(4)
                        .build()));
        OpenAiResponsesResponseMapper mapper = new OpenAiResponsesResponseMapper();
        OpenAiResponsesResponseMapper.StreamState state = mapper.streamState();

        List<org.springframework.ai.chat.model.ChatResponse> mapped =
                Flux.fromIterable(events)
                        .handle(state::accept)
                        .collectList()
                        .block();

        assertThat(mapped).hasSize(3);
        assertThat(mapped.get(0).getResult().getOutput().getText()).isEqualTo("Checking.");
        assertThat(mapped.get(1).getResult().getOutput().getToolCalls())
                .containsExactly(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        "call_1", "function", "lookup", "{\"id\":\"42\"}"));
        assertThat(mapped.get(1).getResult().getOutput().getMetadata())
                .containsKey(OpenAiResponsesRequestMapper.REASONING_ITEMS_METADATA_KEY);
        assertThat(mapped.get(2).getResult().getMetadata().getFinishReason())
                .isEqualTo("tool_calls");
        assertThat(mapped.get(2).getMetadata().getId()).isEqualTo("resp_1");
        assertThat(mapped.get(2).getMetadata().getUsage().getTotalTokens()).isEqualTo(17);
    }

    private ResponseReasoningItem reasoning() {
        return ResponseReasoningItem.builder()
                .id("rs_1")
                .summary(List.of(ResponseReasoningItem.Summary.builder()
                        .text("Need a lookup.")
                        .build()))
                .encryptedContent("encrypted")
                .status(ResponseReasoningItem.Status.COMPLETED)
                .build();
    }

    private ResponseOutputMessage message() {
        return ResponseOutputMessage.builder()
                .id("msg_1")
                .status(ResponseOutputMessage.Status.COMPLETED)
                .addContent(ResponseOutputText.builder()
                        .text("Checking.")
                        .annotations(List.of())
                        .build())
                .build();
    }

    private ResponseFunctionToolCall toolCall() {
        return ResponseFunctionToolCall.builder()
                .callId("call_1")
                .name("lookup")
                .arguments("{\"id\":\"42\"}")
                .build();
    }

    private Response response(List<ResponseOutputItem> output) {
        ResponseUsage usage = ResponseUsage.builder()
                .inputTokens(12)
                .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                        .cachedTokens(2).build())
                .outputTokens(5)
                .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                        .reasoningTokens(3).build())
                .totalTokens(17)
                .build();
        return Response.builder()
                .id("resp_1")
                .createdAt(1)
                .error(Optional.empty())
                .incompleteDetails(Optional.empty())
                .instructions(Optional.empty())
                .metadata(Optional.empty())
                .model(ChatModel.of("gpt-5.6-terra"))
                .output(output)
                .parallelToolCalls(true)
                .temperature(Optional.empty())
                .toolChoice(ToolChoiceOptions.AUTO)
                .tools(List.of())
                .topP(Optional.empty())
                .status(ResponseStatus.COMPLETED)
                .usage(usage)
                .build();
    }
}
