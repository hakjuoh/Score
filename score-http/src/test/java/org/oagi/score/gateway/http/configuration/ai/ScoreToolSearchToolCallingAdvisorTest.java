package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreToolSearchToolCallingAdvisorTest {

    @Test
    void exposesEveryExactToolNamedInAReadBackRequestAtOnce() {
        ToolCallback listContexts = tool("get_business_contexts");
        ToolCallback getContext = tool("get_business_context");
        ToolCallback listSchemes = tool("get_context_schemes");
        ToolCallback getScheme = tool("get_context_scheme");
        ToolCallback mutation = tool("create_context_scheme");
        ScoreToolSearchToolCallingAdvisor advisor =
                new ScoreToolSearchToolCallingAdvisor(new RegexToolIndex());
        ChatClientRequest initialized = advisor.initializeSession(request(
                List.of(new SystemMessage("system"),
                        new UserMessage("Use get_business_contexts/get_business_context and "
                                + "get_context_schemes/get_context_scheme to read everything back")),
                listContexts, getContext, listSchemes, getScheme, mutation));

        ChatClientRequest prepared = advisor.prepareIteration(initialized);

        assertThat(callbackNames(prepared))
                .containsExactly("toolSearchTool", "get_business_contexts",
                        "get_business_context", "get_context_schemes", "get_context_scheme")
                .doesNotContain("create_context_scheme");
    }

    @Test
    void accumulatesAllToolsReturnedByParallelSearchCallsInOneModelTurn() {
        ToolCallback createCategory = tool("create_context_category");
        ToolCallback createScheme = tool("create_context_scheme");
        ToolCallback createValue = tool("create_context_scheme_value");
        ToolCallback readScheme = tool("get_context_scheme");
        ScoreToolSearchToolCallingAdvisor advisor =
                new ScoreToolSearchToolCallingAdvisor(new RegexToolIndex());
        ChatClientRequest initialized = advisor.initializeSession(request(
                List.of(new SystemMessage("system"), new UserMessage("Import everything")),
                createCategory, createScheme, createValue, readScheme));
        List<org.springframework.ai.chat.messages.Message> iteration = new ArrayList<>(
                initialized.prompt().getInstructions());
        iteration.add(searchResponse("search-1",
                "[\"create_context_category\",\"create_context_scheme\"]"));
        iteration.add(searchResponse("search-2",
                "[\"create_context_scheme_value\",\"get_context_scheme\"]"));
        ChatClientRequest next = initialized.mutate()
                .prompt(new Prompt(iteration, initialized.prompt().getOptions()))
                .build();

        ChatClientRequest prepared = advisor.prepareIteration(next);

        assertThat(callbackNames(prepared)).containsExactly(
                "toolSearchTool",
                "create_context_category",
                "create_context_scheme",
                "create_context_scheme_value",
                "get_context_scheme");
    }

    @Test
    void asksEveryRuntimeModelToSearchForTheWholeWorkflowUpFront() {
        ScoreToolSearchToolCallingAdvisor advisor =
                new ScoreToolSearchToolCallingAdvisor(new RegexToolIndex());

        ChatClientRequest initialized = advisor.initializeSession(request(
                List.of(new SystemMessage("base system"), new UserMessage("Import this file")),
                tool("create_dt")));

        assertThat(initialized.prompt().getSystemMessage().getText())
                .contains("comprehensive search with maxResults 10",
                        "all necessary searches", "parallel in the same response",
                        "Never write or simulate `[Tool call: ...]`",
                        "`[Tool: ...]`",
                        "structured tool interface");
    }

    private ChatClientRequest request(List<org.springframework.ai.chat.messages.Message> messages,
                                      ToolCallback... callbacks) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .build();
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages, options))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "conversation-1"))
                .build();
    }

    private ToolResponseMessage searchResponse(String id, String data) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(id, "toolSearchTool", data))).build();
    }

    private List<String> callbackNames(ChatClientRequest request) {
        ToolCallingChatOptions options =
                (ToolCallingChatOptions) request.prompt().getOptions();
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private ToolCallback tool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\"}")
                .build());
        return callback;
    }
}
