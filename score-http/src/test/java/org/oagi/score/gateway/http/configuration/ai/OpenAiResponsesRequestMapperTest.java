package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiResponsesRequestMapperTest {

    @Test
    void mapsReasoningToolsAndToolContinuationToResponsesApiFields() {
        ToolCallingManager tools = mock(ToolCallingManager.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("gpt-5.6-terra")
                .deploymentName("gpt-5.6-terra")
                .reasoningEffort("high")
                .maxCompletionTokens(8192)
                .parallelToolCalls(true)
                .promptCacheKey("route-cache")
                .build();
        when(tools.resolveToolDefinitions(options)).thenReturn(List.of(
                ToolDefinition.builder()
                        .name("lookup")
                        .description("Look up a record.")
                        .inputSchema("""
                                {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}
                                """)
                        .build()));
        OpenAiResponsesRequestMapper mapper = new OpenAiResponsesRequestMapper(
                tools, new ObjectMapper());
        AssistantMessage assistant = AssistantMessage.builder()
                .content("Checking.")
                .properties(Map.of(
                        OpenAiResponsesRequestMapper.REASONING_ITEMS_METADATA_KEY,
                        List.of(Map.of(
                                "id", "rs_1",
                                "encryptedContent", "encrypted",
                                "summary", List.of("Need a lookup."),
                                "status", "completed"))))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "lookup", "{\"id\":\"42\"}")))
                .build();

        ResponseCreateParams request = mapper.create(List.of(
                new SystemMessage("Be precise."),
                new UserMessage("Find record 42."),
                assistant,
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(
                                "call_1", "lookup", "{\"name\":\"Example\"}")))
                        .build()), options);

        assertThat(request.model().orElseThrow().asString()).isEqualTo("gpt-5.6-terra");
        assertThat(request.maxOutputTokens()).contains(8192L);
        assertThat(request.reasoning().orElseThrow().effort().orElseThrow().asString())
                .isEqualTo("high");
        assertThat(request.include().orElseThrow())
                .contains(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        assertThat(request.promptCacheKey()).contains("route-cache");
        assertThat(request.parallelToolCalls()).contains(true);
        assertThat(request.tools().orElseThrow()).singleElement().satisfies(tool -> {
            assertThat(tool.isFunction()).isTrue();
            assertThat(tool.asFunction().name()).isEqualTo("lookup");
            assertThat(tool.asFunction().parameters()).isPresent();
            assertThat(tool.asFunction().strict()).contains(false);
        });

        var input = request.input().orElseThrow().asResponse();
        assertThat(input).hasSize(6);
        assertThat(input.get(0).asEasyInputMessage().role().asString()).isEqualTo("system");
        assertThat(input.get(1).asEasyInputMessage().role().asString()).isEqualTo("user");
        assertThat(input.get(2).asReasoning().encryptedContent()).contains("encrypted");
        assertThat(input.get(3).asEasyInputMessage().role().asString()).isEqualTo("assistant");
        assertThat(input.get(4).asFunctionCall().callId()).isEqualTo("call_1");
        assertThat(input.get(5).asFunctionCallOutput().callId()).isEqualTo("call_1");
        assertThat(input.get(5).asFunctionCallOutput().output().asString())
                .isEqualTo("{\"name\":\"Example\"}");
    }
}
