package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.MutationConfirmation;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationToolGuard;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiSystemPrompt;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiRuntimeMcpToolsTest {

    @org.junit.jupiter.api.Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resumesTheApprovedMutationAndContinuesUntilReadBack() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        ConnectCenterMcpClientFactory mcpClients = mock(ConnectCenterMcpClientFactory.class);
        ToolSearchToolCallingAdvisor toolSearchAdvisor = mock(ToolSearchToolCallingAdvisor.class);
        ScoreAiSystemPrompt systemPrompt = new ScoreAiSystemPrompt(new ByteArrayResource(
                "System prompt. Page: {pageContext}".getBytes(StandardCharsets.UTF_8)));
        AiMutationConfirmationService confirmations = mock(AiMutationConfirmationService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        AiMutationToolGuard mutationGuard = new AiMutationToolGuard(confirmations, requests);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);
        AtomicReference<ToolCallbackProvider> installedTools = new AtomicReference<>();
        when(models.clientBuilder("configured-model")).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenAnswer(invocation -> {
            installedTools.set((ToolCallbackProvider) invocation.getArgument(0));
            return builder;
        });
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(responseSpec);

        ToolCallback create = tool("create_business_context", "{\"id\":101}");
        ToolCallback read = tool("get_business_context", "{\"id\":101,\"name\":\"Approved\"}");
        AtomicInteger calls = new AtomicInteger();
        when(responseSpec.chatResponse()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                ToolCallback readBack = java.util.Arrays.stream(installedTools.get().getToolCallbacks())
                        .filter(callback -> callback.getToolDefinition().name().equals("get_business_context"))
                        .findFirst().orElseThrow();
                readBack.call("{\"id\":101}");
                return Flux.just(response("Verified after read-back."));
            }
            return Flux.just(response("Created, but not verified."));
        });

        McpSyncClient mcpClient = mock(McpSyncClient.class);
        when(mcpClients.open(any(ScoreUser.class))).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(
                        mcpClient, () -> new ToolCallback[]{create, read}));
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new AiMutationAuthorization(true, null));
        when(requests.mutationStarted("request-1")).thenReturn(true);
        AnthropicRuntimeOptions runtimeOptions = mock(AnthropicRuntimeOptions.class);
        AnthropicChatOptions chatOptions = anthropicRequestOptions();
        when(runtimeOptions.options("configured-model", "high", java.util.Map.of()))
                .thenReturn(chatOptions);

        String arguments = "{\"name\":\"Approved\"}";
        ChatRequest request = new ChatRequest(
                "Create it", "request-1", null, "conversation-1", "test page", List.of(),
                new MutationConfirmation("confirmation-1", "grant", "create_business_context", arguments),
                "configured-model", "high", ScoreAiModelRegistry.CLAUDE, java.util.Map.of(), "ask");
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(ScoreChatMemoryRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                requester, "conversation-1", "request-1", ignored -> {});
        AiRuntime runtime = new ClaudeRuntime(models, mcpClients, toolSearchAdvisor,
                systemPrompt, mutationGuard, runtimeOptions);

        AiRuntime.Result result = runtime.execute(new AiRuntime.Context(
                request, List.of(), new UserMessage("Create it"), requester, recorder));

        assertThat(result.answer()).isEqualTo("Verified after read-back.");
        verify(create).call(eq(arguments), any(org.springframework.ai.chat.model.ToolContext.class));
        verify(read).call(eq("{\"id\":101}"), any(org.springframework.ai.chat.model.ToolContext.class));
        verify(client, times(2)).prompt();
        verify(builder, never()).defaultAdvisors(eq(toolSearchAdvisor));
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void letsTheModelApplyAUserApprovedRevisionOnceThenContinuesUntilReadBack() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        ConnectCenterMcpClientFactory mcpClients = mock(ConnectCenterMcpClientFactory.class);
        ToolSearchToolCallingAdvisor toolSearchAdvisor = mock(ToolSearchToolCallingAdvisor.class);
        ScoreAiSystemPrompt systemPrompt = new ScoreAiSystemPrompt(new ByteArrayResource(
                "System prompt. Page: {pageContext}".getBytes(StandardCharsets.UTF_8)));
        AiMutationConfirmationService confirmations = mock(AiMutationConfirmationService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        AiMutationToolGuard mutationGuard = new AiMutationToolGuard(confirmations, requests);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);
        AtomicReference<ToolCallbackProvider> installedTools = new AtomicReference<>();
        when(models.clientBuilder("configured-model")).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenAnswer(invocation -> {
            installedTools.set((ToolCallbackProvider) invocation.getArgument(0));
            return builder;
        });
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(responseSpec);

        String revisedArguments = "{\"name\":\"Revised Business Context\"}";
        ToolCallback create = tool("create_business_context", "{\"id\":202}");
        ToolCallback read = tool("get_business_context",
                "{\"id\":202,\"name\":\"Revised Business Context\"}");
        AtomicInteger calls = new AtomicInteger();
        when(responseSpec.chatResponse()).thenAnswer(invocation -> {
            ToolCallbackProvider tools = installedTools.get();
            if (calls.incrementAndGet() == 1) {
                java.util.Arrays.stream(tools.getToolCallbacks())
                        .filter(callback -> callback.getToolDefinition().name()
                                .equals("create_business_context"))
                        .findFirst().orElseThrow()
                        .call(revisedArguments);
                return Flux.just(response("Created, but not verified."));
            }
            java.util.Arrays.stream(tools.getToolCallbacks())
                    .filter(callback -> callback.getToolDefinition().name()
                            .equals("get_business_context"))
                    .findFirst().orElseThrow()
                    .call("{\"id\":202}");
            return Flux.just(response("Verified the revised business context."));
        });

        McpSyncClient mcpClient = mock(McpSyncClient.class);
        when(mcpClients.open(any(ScoreUser.class))).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(
                        mcpClient, () -> new ToolCallback[]{create, read}));
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new AiMutationAuthorization(true, null));
        when(requests.mutationStarted("request-2")).thenReturn(true);
        AnthropicRuntimeOptions runtimeOptions = mock(AnthropicRuntimeOptions.class);
        AnthropicChatOptions revisedChatOptions = anthropicRequestOptions();
        when(runtimeOptions.options("configured-model", "high", java.util.Map.of()))
                .thenReturn(revisedChatOptions);

        String revisionPrompt = "Use the name Revised Business Context";
        MutationConfirmation revision = new MutationConfirmation(
                "confirmation-1", "grant", "create_business_context", null,
                "REVISED", revisionPrompt);
        ChatRequest request = new ChatRequest(
                revisionPrompt, "request-2", null, "conversation-1", "test page", List.of(),
                revision, "configured-model", "high", ScoreAiModelRegistry.CLAUDE,
                java.util.Map.of(), "ask");
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(ScoreChatMemoryRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                requester, "conversation-1", "request-2", ignored -> {});
        AiRuntime runtime = new ClaudeRuntime(models, mcpClients, toolSearchAdvisor,
                systemPrompt, mutationGuard, runtimeOptions);

        AiRuntime.Result result = runtime.execute(new AiRuntime.Context(
                request, List.of(), new UserMessage(revisionPrompt), requester, recorder));

        assertThat(result.answer()).isEqualTo("Verified the revised business context.");
        verify(create).call(eq(revisedArguments),
                any(org.springframework.ai.chat.model.ToolContext.class));
        verify(read).call(eq("{\"id\":202}"),
                any(org.springframework.ai.chat.model.ToolContext.class));
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(requestSpec, times(2)).messages(messages.capture());
        assertThat(messages.getAllValues().getFirst())
                .anySatisfy(message -> assertThat(message)
                        .isInstanceOfSatisfying(
                                org.springframework.ai.chat.messages.SystemMessage.class,
                                system -> assertThat(system.getText())
                                        .contains("revised and approved exactly one",
                                                "create_business_context")));
        verify(builder, never()).defaultAdvisors(eq(toolSearchAdvisor));
    }

    @TestFactory
    Stream<DynamicTest> everyRuntimeInstallsRequesterScopedConnectCenterMcpTools() {
        return Stream.of(
                new RuntimeCase(ScoreAiModelRegistry.DEFAULT),
                new RuntimeCase(ScoreAiModelRegistry.CLAUDE),
                new RuntimeCase(ScoreAiModelRegistry.OPENAI)
        ).map(runtimeCase -> DynamicTest.dynamicTest(runtimeCase.name(), () -> executeAndVerify(runtimeCase)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeAndVerify(RuntimeCase runtimeCase) {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        ConnectCenterMcpClientFactory mcpClients = mock(ConnectCenterMcpClientFactory.class);
        ToolSearchToolCallingAdvisor toolSearchAdvisor = mock(ToolSearchToolCallingAdvisor.class);
        ScoreAiSystemPrompt systemPrompt = new ScoreAiSystemPrompt(new ByteArrayResource(
                "System prompt. Page: {pageContext}".getBytes(StandardCharsets.UTF_8)));

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(models.clientBuilder("configured-model")).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.messages(any(Message[].class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("private reasoning").properties(java.util.Map.of("thinking", true)).build()))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("ans")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("wer"))))));

        ToolCallback mcpTool = mock(ToolCallback.class);
        ToolCallbackProvider mcpTools = () -> new ToolCallback[]{mcpTool};
        McpSyncClient mcpClient = mock(McpSyncClient.class);
        when(mcpClients.open(any(ScoreUser.class))).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(mcpClient, mcpTools));

        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(ScoreChatMemoryRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                requester, "conversation-1", "request-1", ignored -> {});
        ChatRequest request = new ChatRequest(
                "hello", "request-1", null, "conversation-1", "test page", List.of(), null,
                "configured-model", "high", runtimeCase.name());
        UserMessage userMessage = new UserMessage("hello");
        AiRuntime runtime = runtime(runtimeCase.name(), models, mcpClients, toolSearchAdvisor, systemPrompt);

        AiRuntime.Result result = runtime.execute(new AiRuntime.Context(
                request, List.of(), userMessage, requester, recorder));

        assertThat(result.answer()).isEqualTo("ans wer");
        verify(mcpClients).open(requester);
        ArgumentCaptor<Object[]> installedTools = ArgumentCaptor.forClass(Object[].class);
        verify(builder).defaultTools(installedTools.capture());
        assertThat(installedTools.getValue()).hasSize(1);
        assertThat(installedTools.getValue()[0]).isInstanceOf(ToolCallbackProvider.class);
        assertThat(((ToolCallbackProvider) installedTools.getValue()[0]).getToolCallbacks()).hasSize(1);
        verify(builder).defaultAdvisors(eq(toolSearchAdvisor));
        verify(mcpClient).closeGracefully();
    }

    private AiRuntime runtime(String name, ScoreAiModelRegistry models,
                              ConnectCenterMcpClientFactory mcpClients,
                              ToolSearchToolCallingAdvisor toolSearchAdvisor,
                              ScoreAiSystemPrompt systemPrompt) {
        if (ScoreAiModelRegistry.CLAUDE.equals(name)) {
            AnthropicRuntimeOptions options = mock(AnthropicRuntimeOptions.class);
            AnthropicChatOptions requestOptions = anthropicRequestOptions();
            when(options.options("configured-model", "high", null))
                    .thenReturn(requestOptions);
            return new ClaudeRuntime(models, mcpClients, toolSearchAdvisor, systemPrompt, options);
        }
        if (ScoreAiModelRegistry.OPENAI.equals(name)) {
            OpenAiRuntimeOptions options = mock(OpenAiRuntimeOptions.class);
            OpenAiChatOptions requestOptions = openAiRequestOptions();
            when(options.options("configured-model", "high", null))
                    .thenReturn(requestOptions);
            return new OpenAIRuntime(models, mcpClients, toolSearchAdvisor, systemPrompt, options);
        }
        AnthropicRuntimeOptions anthropicOptions = mock(AnthropicRuntimeOptions.class);
        AnthropicChatOptions requestOptions = anthropicRequestOptions();
        when(models.providerType("configured-model")).thenReturn("anthropic");
        when(anthropicOptions.options("configured-model", "high", java.util.Map.of()))
                .thenReturn(requestOptions);
        return new SpringAIRuntime(models, mcpClients, toolSearchAdvisor, systemPrompt,
                anthropicOptions, mock(OpenAiRuntimeOptions.class));
    }

    private AnthropicChatOptions anthropicRequestOptions() {
        AnthropicChatOptions options = mock(AnthropicChatOptions.class);
        AnthropicChatOptions.Builder builder = mock(AnthropicChatOptions.Builder.class);
        when(options.mutate()).thenReturn(builder);
        return options;
    }

    private OpenAiChatOptions openAiRequestOptions() {
        OpenAiChatOptions options = mock(OpenAiChatOptions.class);
        OpenAiChatOptions.Builder builder = mock(OpenAiChatOptions.Builder.class);
        when(options.mutate()).thenReturn(builder);
        return options;
    }

    private ToolCallback tool(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(org.springframework.ai.chat.model.ToolContext.class)))
                .thenReturn(result);
        return callback;
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private record RuntimeCase(String name) {}
}
