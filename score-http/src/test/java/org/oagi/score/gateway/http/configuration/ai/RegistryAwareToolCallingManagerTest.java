package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistryAwareToolCallingManagerTest {

    @Test
    void executesARequestScopedToolThatWasNotExposedInTheSearchIteration() {
        ToolCallback visible = tool("toolSearchTool", "[]");
        ToolCallback registered = tool("get_context_schemes", "{\"items\":[]}");
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(visible)
                .toolContext(RegistryAwareToolCallingManager.TOOL_CALLBACK_REGISTRY_CONTEXT_KEY,
                        Map.of("get_context_schemes", registered))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage(
                "Call get_context_schemes now")), options);
        ChatResponse response = toolCallResponse("get_context_schemes", "{}");

        var result = new RegistryAwareToolCallingManager().executeToolCalls(prompt, response);

        verify(registered).call(eq("{}"), any(ToolContext.class));
        assertThat(result.conversationHistory().getLast())
                .isInstanceOfSatisfying(ToolResponseMessage.class,
                        message -> assertThat(message.getResponses().getFirst().responseData())
                                .isEqualTo("{\"items\":[]}"));
    }

    @Test
    void stillRejectsNamesThatAreNotInTheRequesterScopedRegistry() {
        ToolCallback visible = tool("toolSearchTool", "[]");
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(visible)
                .toolContext(RegistryAwareToolCallingManager.TOOL_CALLBACK_REGISTRY_CONTEXT_KEY,
                        Map.of())
                .build();

        assertThatThrownBy(() -> new RegistryAwareToolCallingManager().executeToolCalls(
                new Prompt(List.of(new UserMessage("Call invented_tool")), options),
                toolCallResponse("invented_tool", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ToolCallback found for tool name: invented_tool");
    }

    @Test
    void rejectsAnUnknownParallelCallBeforeExecutingAnyRegisteredCall() {
        ToolCallback registered = tool("get_context_schemes", "{\"items\":[]}");
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tool("toolSearchTool", "[]"))
                .toolContext(RegistryAwareToolCallingManager.TOOL_CALLBACK_REGISTRY_CONTEXT_KEY,
                        Map.of("get_context_schemes", registered))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall(
                                "call-1", "function", "get_context_schemes", "{}"),
                        new AssistantMessage.ToolCall(
                                "call-2", "function", "invented_tool", "{}")))
                .build())));

        assertThatThrownBy(() -> new RegistryAwareToolCallingManager().executeToolCalls(
                new Prompt(List.of(new UserMessage("Use both")), options), response))
                .hasMessageContaining("No ToolCallback found for tool name: invented_tool");
        verify(registered, never()).call(any(String.class), any(ToolContext.class));
    }

    private ToolCallback tool(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\"}")
                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(any(String.class), any(ToolContext.class))).thenReturn(result);
        return callback;
    }

    private ChatResponse toolCallResponse(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", name, arguments)))
                .build())));
    }
}
