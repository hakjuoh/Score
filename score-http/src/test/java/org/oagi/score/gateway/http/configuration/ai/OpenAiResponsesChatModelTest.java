package org.oagi.score.gateway.http.configuration.ai;

import com.openai.client.OpenAIClient;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCompactionItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiResponsesChatModelTest {

    @Test
    void usesResponsesToolsAndPreservesReasoningItemsAcrossTheToolLoop() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);

        ResponseReasoningItem reasoning = ResponseReasoningItem.builder()
                .id("rs_1").summary(List.of()).build();
        ResponseFunctionToolCall functionCall = ResponseFunctionToolCall.builder()
                .id("fc_1")
                .callId("call_1")
                .name("get_business_context")
                .arguments("{\"name\":\"Example\"}")
                .status(ResponseFunctionToolCall.Status.COMPLETED)
                .build();
        Response firstProviderResponse = response("resp_1", List.of(
                ResponseOutputItem.ofReasoning(reasoning),
                ResponseOutputItem.ofFunctionCall(functionCall)));
        Response secondProviderResponse = response("resp_2", List.of(ResponseOutputItem.ofMessage(
                ResponseOutputMessage.builder()
                        .id("msg_1")
                        .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                                ResponseOutputText.builder()
                                        .annotations(List.of())
                                        .text("Imported successfully.")
                                        .build())))
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build())));
        when(responses.create(any(ResponseCreateParams.class)))
                .thenReturn(firstProviderResponse, secondProviderResponse);

        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name("get_business_context")
                .description("Reads a business context")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                .build();
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("{\"found\":true}");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("gpt-5.6-sol")
                .reasoningEffort("high")
                .build();
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(client, options);

        String answer = ChatClient.builder(model)
                .defaultTools(callback)
                .build()
                .prompt()
                .options(options.mutate())
                .system("Use connect-center tools.")
                .user("Import this manifest.")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(answer).isEqualTo("Imported successfully.");
        verify(callback).call(eq("{\"name\":\"Example\"}"), any(ToolContext.class));

        ArgumentCaptor<ResponseCreateParams> requests = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responses, org.mockito.Mockito.times(2)).create(requests.capture());
        ResponseCreateParams initial = requests.getAllValues().get(0);
        assertThat(initial.instructions()).contains("Use connect-center tools.");
        assertThat(initial.reasoning()).get().extracting(value -> value.effort().orElseThrow().asString())
                .isEqualTo("high");
        assertThat(initial.tools().orElseThrow()).singleElement().satisfies(tool -> {
            assertThat(tool.isFunction()).isTrue();
            assertThat(tool.asFunction().name()).isEqualTo("get_business_context");
            assertThat(tool.asFunction().strict()).contains(false);
        });

        List<com.openai.models.responses.ResponseInputItem> continuation = requests.getAllValues().get(1)
                .input().orElseThrow().asResponse();
        assertThat(continuation).anyMatch(item -> item.isReasoning()
                && item.asReasoning().id().equals("rs_1"));
        assertThat(continuation).anyMatch(item -> item.isFunctionCall()
                && item.asFunctionCall().callId().equals("call_1"));
        assertThat(continuation).anyMatch(item -> item.isFunctionCallOutput()
                && item.asFunctionCallOutput().callId().equals("call_1")
                && item.asFunctionCallOutput().output().asString().equals("{\"found\":true}"));
    }

    @Test
    void enablesServerCompactionAndCarriesTheOpaqueItemAcrossAToolContinuation() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);
        ResponseReasoningItem staleReasoning = ResponseReasoningItem.builder()
                .id("rs_stale").summary(List.of()).build();
        ResponseCompactionItem compaction = ResponseCompactionItem.builder()
                .id("cmp_1").encryptedContent("encrypted-state").build();
        ResponseFunctionToolCall functionCall = ResponseFunctionToolCall.builder()
                .id("fc_1").callId("call_1").name("get_business_context")
                .arguments("{}").status(ResponseFunctionToolCall.Status.COMPLETED).build();
        Response first = response("resp_1", List.of(ResponseOutputItem.ofReasoning(staleReasoning),
                ResponseOutputItem.ofCompaction(compaction),
                ResponseOutputItem.ofFunctionCall(functionCall)));
        Response second = response("resp_2", List.of(ResponseOutputItem.ofMessage(ResponseOutputMessage.builder()
                        .id("msg_1").content(List.of(ResponseOutputMessage.Content.ofOutputText(
                                ResponseOutputText.builder().annotations(List.of()).text("Done.").build())))
                        .status(ResponseOutputMessage.Status.COMPLETED).build())));
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(first, second);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_business_context").description("Reads data")
                .inputSchema("{\"type\":\"object\"}").build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("{\"ok\":true}");
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-5.6-sol").build();
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(client, options, 150000L);

        String answer = ChatClient.builder(model).defaultTools(callback).build().prompt()
                .options(options.mutate()).user("Continue a long task.").stream().content()
                .collectList().map(parts -> String.join("", parts)).block();

        assertThat(answer).isEqualTo("Done.");
        ArgumentCaptor<ResponseCreateParams> requests = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responses, org.mockito.Mockito.times(2)).create(requests.capture());
        assertThat(requests.getAllValues().getFirst().contextManagement().orElseThrow())
                .singleElement().satisfies(value -> {
                    assertThat(value.type()).isEqualTo("compaction");
                    assertThat(value.compactThreshold()).contains(150000L);
                });
        List<com.openai.models.responses.ResponseInputItem> continuation = requests.getAllValues().get(1)
                .input().orElseThrow().asResponse();
        assertThat(continuation).anyMatch(item -> item.isCompaction()
                && item.asCompaction().encryptedContent().equals("encrypted-state"));
        assertThat(continuation).noneMatch(item -> item.isReasoning()
                && item.asReasoning().id().equals("rs_stale"));
    }

    private Response response(String id, List<ResponseOutputItem> output) {
        Response response = mock(Response.class);
        when(response.id()).thenReturn(id);
        when(response.createdAt()).thenReturn(1.0);
        when(response.model()).thenReturn(ResponsesModel.ofString("gpt-5.6-sol"));
        when(response.output()).thenReturn(output);
        when(response.status()).thenReturn(Optional.of(ResponseStatus.COMPLETED));
        when(response.error()).thenReturn(Optional.empty());
        when(response.usage()).thenReturn(Optional.empty());
        return response;
    }
}
