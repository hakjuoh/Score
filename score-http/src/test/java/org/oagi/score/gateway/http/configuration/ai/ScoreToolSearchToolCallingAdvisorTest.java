package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreToolSearchToolCallingAdvisorTest {

    private static final String TEST_SYSTEM_MESSAGE = "Use tool search for the whole workflow.";

    @Test
    void exposesEveryExactToolNamedInAReadBackRequestAtOnce() {
        ToolCallback listContexts = tool("get_business_contexts");
        ToolCallback getContext = tool("get_business_context");
        ToolCallback listSchemes = tool("get_context_schemes");
        ToolCallback getScheme = tool("get_context_scheme");
        ToolCallback change = tool("create_context_scheme");
        ScoreToolSearchToolCallingAdvisor advisor =
                new ScoreToolSearchToolCallingAdvisor(
                        new ScoreToolIndex(), TEST_SYSTEM_MESSAGE);
        ChatClientRequest initialized = advisor.initializeSession(request(
                List.of(new SystemMessage("system"),
                        new UserMessage("Use get_business_contexts/get_business_context and "
                                + "get_context_schemes/get_context_scheme to read everything back")),
                listContexts, getContext, listSchemes, getScheme, change));

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
                new ScoreToolSearchToolCallingAdvisor(
                        new ScoreToolIndex(), TEST_SYSTEM_MESSAGE);
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
    void asksEveryModelToSearchForTheWholeWorkflowUpFront() {
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        ScoreToolSearchToolCallingAdvisor advisor = (ScoreToolSearchToolCallingAdvisor)
                new ScoreAiConfiguration().scoreAiToolSearchAdvisor(
                        new ScoreToolIndex(), catalog);
        String instruction = catalog.systemDefinition("tool-search-advisor")
                .instruction().render().value();

        ChatClientRequest initialized = advisor.initializeSession(request(
                List.of(new SystemMessage("base system"), new UserMessage("Import this file")),
                tool("create_dt")));

        assertThat(initialized.prompt().getSystemMessage().getText())
                .isEqualTo("base system\n" + instruction + "\n\n"
                        + "<available-deferred-tools>\ncreate_dt\n"
                        + "</available-deferred-tools>")
                .contains("tool-search agent", "select:name1,name2",
                        "more than 10 tools", "searches in parallel in the same response",
                        "Never write or simulate `[Tool call: ...]`",
                        "`[Tool: ...]`",
                        "structured tool interface",
                        "<available-deferred-tools>", "create_dt",
                        "</available-deferred-tools>");
    }

    @Test
    void preservesTheResourcePromptBoundaryWithoutDeferredTools() {
        ScoreToolSearchToolCallingAdvisor advisor =
                new ScoreToolSearchToolCallingAdvisor(
                        new ScoreToolIndex(), TEST_SYSTEM_MESSAGE);

        ChatClientRequest initialized = advisor.initializeSession(request(
                List.of(new SystemMessage("base system"), new UserMessage("Answer directly"))));

        assertThat(initialized.prompt().getSystemMessage().getText())
                .isEqualTo("base system\n" + TEST_SYSTEM_MESSAGE + "\n");
    }

    @Test
    void rejectsABlankSystemMessage() {
        assertThatThrownBy(() -> new ScoreToolSearchToolCallingAdvisor(
                new ScoreToolIndex(), " \n "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
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
