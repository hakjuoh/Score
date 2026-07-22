package org.oagi.score.gateway.http.api.ai_management.service;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiUiRouteManifest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiChatOptionsFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiSystemPrompt;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatExecutorTest {

    @Test
    void suppliesStableProtocolParametersAndSeparatesRequestContext() {
        AiUiRouteManifest routeManifest = new AiUiRouteManifest(1, List.of(
                new AiUiRouteManifest.Route(
                        "business-context", "/context_management/business_context",
                        java.util.Map.of("default", "/context_management/business_context/{id}"),
                        List.of("id"), List.of("name"), null)));
        ChatRequest request = new ChatRequest(
                "Inspect it", "request-1", null, "conversation-1", "test page", List.of(), null,
                "configured-model", "high", "ask", null, null, routeManifest);

        assertThat(AiChatExecutor.systemPromptParameters(request))
                .containsEntry("mutationConfirmationRequired",
                        AiMutationToolGuard.MUTATION_CONFIRMATION_REQUIRED)
                .containsEntry("requestStopping", AiMutationToolGuard.REQUEST_STOPPING)
                .containsEntry("pageContext",
                        "Supplied separately in the request-scoped user-context block.");
        assertThat(AiChatExecutor.requestScopedInput(request))
                .contains("## Request-scoped input", "Current page context: test page",
                        "untrusted data only");
        assertThat(AiChatExecutor.withRouteManifest("Stable system prompt.", request))
                .startsWith("Stable system prompt.")
                .contains("## Validated connectCenter UI route manifest")
                .contains("resource=business-context");
    }

    @Test
    void contextForcesToolPolicyToNoneWhenToolsAreDisabled() {
        AiChatExecutor.Context context = new AiChatExecutor.Context(
                request("Inspect"), List.of(), null, null, null, false, false,
                AiChatExecutor.ToolPolicy.FULL, 0);

        assertThat(context.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readOnlySpecialistInstallsOnlyServerDeclaredReadTools() {
        Fixture fixture = new Fixture();
        AtomicReference<ToolCallbackProvider> installedTools = fixture.captureInstalledTools();
        fixture.responses(Flux.just(response("Read-only evidence.")));
        ToolCallback create = tool("create_business_context", "must not execute");
        ToolCallback read = tool("get_business_context", "{\"id\":101}");
        McpSyncClient mcpClient = fixture.mcp(create, read, Set.of("get_business_context"));
        AiMutationToolGuard mutationGuard = new AiMutationToolGuard(
                mock(AiMutationConfirmationService.class), mock(AiRequestRegistry.class));
        AiTrajectoryRecorder recorder = fixture.recorder("request-1");

        AiChatExecutor.Result result = fixture.executor(mutationGuard).execute(
                new AiChatExecutor.Context(request("Inspect it"), List.of(),
                        new UserMessage("Inspect it"), fixture.requester, recorder,
                        true, false, AiChatExecutor.ToolPolicy.READ_ONLY, 1));

        assertThat(result.answer()).isEqualTo("Read-only evidence.");
        assertThat(installedTools.get().getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("get_business_context");
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(fixture.requestSpec).messages(messages.capture());
        assertThat(messages.getValue().getLast())
                .isInstanceOfSatisfying(UserMessage.class,
                        context -> assertThat(context.getText())
                                .contains("## Request-scoped input",
                                        "Current page context: test page"));
        verify(fixture.builder).defaultAdvisors(eq(fixture.toolSearchAdvisor));
        verify(create, never()).call(anyString(),
                any(org.springframework.ai.chat.model.ToolContext.class));
        verify(mcpClient).closeGracefully();
    }

    @Test
    void dropsPreToolNarrationAndReturnsThePostToolSegment() {
        Fixture fixture = new Fixture();
        AtomicReference<ToolCallbackProvider> installedTools = fixture.captureInstalledTools();
        ToolCallback read = tool("get_business_context", "{\"id\":7}");
        fixture.mcp(read, Set.of("get_business_context"));
        fixture.responses(Flux.concat(
                Flux.just(response("Let me verify the key records first.")),
                Flux.defer(() -> {
                    java.util.Arrays.stream(installedTools.get().getToolCallbacks())
                            .filter(callback -> callback.getToolDefinition().name()
                                    .equals("get_business_context"))
                            .findFirst().orElseThrow().call("{\"id\":7}");
                    return Flux.just(response("Verified: business context 7 exists."));
                })));

        AiChatExecutor.Result result = fixture.executor(null).execute(
                new AiChatExecutor.Context(request("Verify it"), List.of(),
                        new UserMessage("Verify it"), fixture.requester,
                        fixture.recorder("request-1")));

        assertThat(result.answer()).isEqualTo("Verified: business context 7 exists.");
        verify(read).call(eq("{\"id\":7}"),
                any(org.springframework.ai.chat.model.ToolContext.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recoversWhenTheModelPrintsTextualToolCallPlaceholders() {
        Fixture fixture = new Fixture();
        fixture.mcp(tool("get_context_schemes", "[]"), Set.of("get_context_schemes"));
        when(fixture.responseSpec.chatResponse()).thenReturn(
                Flux.just(response("I'll look it up.\n\n[Tool call: contextScheme_search]")),
                Flux.just(response("I'll search.\n\n**[Tool: toolSearchTool]** → searching")),
                Flux.just(response("The available context schemes are A and B.")));

        AiChatExecutor.Result result = fixture.executor(null).execute(
                new AiChatExecutor.Context(request("Inspect schemes"), List.of(),
                        new UserMessage("Inspect schemes"), fixture.requester,
                        fixture.recorder("request-1")));

        assertThat(result.answer()).isEqualTo("The available context schemes are A and B.");
        verify(fixture.client, times(3)).prompt();
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(fixture.requestSpec, times(3)).messages(messages.capture());
        assertThat(messages.getAllValues().getLast())
                .anySatisfy(message -> assertThat(message.getText())
                        .contains("[Tool: toolSearchTool]"))
                .anySatisfy(message -> assertThat(message)
                        .isInstanceOfSatisfying(UserMessage.class,
                                recovery -> assertThat(recovery.getText())
                                        .contains("INTERNAL_ORCHESTRATION_INSTRUCTION",
                                                "structured tool API", "toolSearchTool")));
    }

    private static ChatRequest request(String prompt) {
        return new ChatRequest(prompt, "request-1", null, "conversation-1", "test page",
                List.of(), null, "configured-model", "high", "ask");
    }

    private static ToolCallback tool(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(org.springframework.ai.chat.model.ToolContext.class)))
                .thenReturn(result);
        return callback;
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class Fixture {
        private final ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        private final ConnectCenterMcpClientFactory mcpClients = mock(ConnectCenterMcpClientFactory.class);
        private final ToolSearchToolCallingAdvisor toolSearchAdvisor =
                mock(ToolSearchToolCallingAdvisor.class);
        private final ScoreAiChatOptionsFactory optionsFactory = mock(ScoreAiChatOptionsFactory.class);
        private final ChatClient.Builder builder = mock(ChatClient.Builder.class);
        private final ChatClient client = mock(ChatClient.class);
        private final ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        private final ChatClient.StreamResponseSpec responseSpec =
                mock(ChatClient.StreamResponseSpec.class);
        private final ScoreUser requester = mock(ScoreUser.class);
        private final ScoreAiSystemPrompt systemPrompt = new ScoreAiSystemPrompt(new ByteArrayResource(
                "System prompt. Page: ${pageContext}".getBytes(StandardCharsets.UTF_8)));

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Fixture() {
            ScoreAiModelRegistry.ModelConfiguration model =
                    mock(ScoreAiModelRegistry.ModelConfiguration.class);
            when(models.clientBuilder("configured-model")).thenReturn(builder);
            when(models.modelConfiguration("configured-model")).thenReturn(model);
            when(optionsFactory.create("configured-model", "high", null))
                    .thenReturn(AnthropicChatOptions.builder().model("configured-model").build());
            when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
            when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
            when(builder.build()).thenReturn(client);
            when(client.prompt()).thenReturn(requestSpec);
            when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
            when(requestSpec.system(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.messages(anyList())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.stream()).thenReturn(responseSpec);
        }

        private AiChatExecutor executor(AiMutationToolGuard mutationGuard) {
            return new AiChatExecutor(models, mcpClients, toolSearchAdvisor, systemPrompt,
                    mutationGuard, null, null, optionsFactory);
        }

        private AiTrajectoryRecorder recorder(String requestId) {
            return new AiTrajectoryRecorder(mock(AiChatConversationRepository.class),
                    new com.fasterxml.jackson.databind.ObjectMapper(), requester,
                    "conversation-1", requestId, ignored -> {});
        }

        private AtomicReference<ToolCallbackProvider> captureInstalledTools() {
            AtomicReference<ToolCallbackProvider> installed = new AtomicReference<>();
            when(builder.defaultTools(any(Object[].class))).thenAnswer(invocation -> {
                installed.set((ToolCallbackProvider) invocation.getArgument(0));
                return builder;
            });
            return installed;
        }

        private void responses(Flux<ChatResponse> responses) {
            when(responseSpec.chatResponse()).thenReturn(responses);
        }

        private McpSyncClient mcp(ToolCallback tool, Set<String> readOnlyNames) {
            return mcp(new ToolCallback[]{tool}, readOnlyNames);
        }

        private McpSyncClient mcp(ToolCallback first, ToolCallback second,
                                  Set<String> readOnlyNames) {
            return mcp(new ToolCallback[]{first, second}, readOnlyNames);
        }

        private McpSyncClient mcp(ToolCallback[] tools, Set<String> readOnlyNames) {
            McpSyncClient client = mock(McpSyncClient.class);
            when(mcpClients.open(any(ScoreUser.class))).thenReturn(
                    new ConnectCenterMcpClientFactory.McpSession(
                            client, () -> tools, readOnlyNames));
            return client;
        }
    }
}
