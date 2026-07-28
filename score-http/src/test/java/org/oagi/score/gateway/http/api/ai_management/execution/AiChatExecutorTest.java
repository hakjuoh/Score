package org.oagi.score.gateway.http.api.ai_management.execution;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiUiRouteManifest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedMutation;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiCallbackToolSetAdapter;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiToolAdapter;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiChatOptionsFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatExecutorTest {

    private static final Agent.Instruction TEST_INSTRUCTION =
            new Agent.Instruction("Test Agent system instruction.");

    @Test
    void chatUsageReportsOnlyTheCurrentAttemptDelta() {
        AiUsageSnapshot before = new AiUsageSnapshot("node", "agent", 100, 40, 2);
        AiUsageSnapshot after = new AiUsageSnapshot("node", "agent", 130, 52, 3);

        AiUsageSnapshot delta = AiChatExecutor.usageDelta(before, after);

        assertThat(delta.promptTokens()).isEqualTo(30);
        assertThat(delta.completionTokens()).isEqualTo(12);
        assertThat(delta.modelCalls()).isEqualTo(1);
    }

    @Test
    void classifiesRegistryTimeoutAndCancellationEvenWhenTheFailureTypeIsGeneric() {
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        when(requests.isTimingOut("timed-out")).thenReturn(true);
        when(requests.isCancelling("cancelled")).thenReturn(true);

        assertThat(AiChatExecutor.agentFailureEvent(requests, "timed-out",
                new IllegalStateException("provider interrupted")))
                .isEqualTo("agent.run.timed_out");
        assertThat(AiChatExecutor.agentFailureEvent(requests, "cancelled",
                new IllegalStateException("provider interrupted")))
                .isEqualTo("agent.run.cancelled");
    }

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

        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                request, List.of(), new org.oagi.score.gateway.http.api.ai_management.agent.AiMessage.User(
                        request.prompt()), null, null, false, false,
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy.NONE, 0);
        assertThat(execution.instructionParameters())
                .containsEntry("mutationConfirmationRequired",
                        AiMutationToolGuard.MUTATION_CONFIRMATION_REQUIRED)
                .containsEntry("requestStopping", AiMutationToolGuard.REQUEST_STOPPING)
                .containsEntry("pageContext",
                        "Supplied separately in the request-scoped user-context block.");
        assertThat(AiChatExecutor.requestScopedInput(request))
                .contains("## Request-scoped input", "Current page context: test page",
                        "untrusted data only");
        assertThat(execution.withAgentIdentity("root-agent",
                        ExecutionScope.Purpose.USER_RESPONSE)
                .finalizeInstruction(new Agent.Instruction("Stable system prompt.")).value())
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
    void executesTheIdentityAndInstructionBoundByTheRunnerForEachRun() {
        Fixture fixture = new Fixture();
        fixture.responses(Flux.just(response("First response.")),
                Flux.just(response("Second response.")));
        AiChatExecutor executor = fixture.executor(null);

        AiChatExecutor.Result first = executor.execute(new AiChatExecutor.Context(
                request("First request"), List.of(), new UserMessage("First request"),
                fixture.requester, fixture.recorder("request-1"))
                        .withAgentIdentity("external-root-agent",
                                ExecutionScope.Purpose.USER_RESPONSE),
                new Agent.Instruction("First external instruction."));

        assertThat(first.traceMetadata()).containsEntry("agentId", "external-root-agent");
        verify(fixture.systemSpec).text("First external instruction.");

        AiChatExecutor.Result second = executor.execute(new AiChatExecutor.Context(
                request("Second request"), List.of(), new UserMessage("Second request"),
                fixture.requester, fixture.recorder("request-2"))
                        .withAgentIdentity("reloaded-root-agent",
                                ExecutionScope.Purpose.USER_RESPONSE),
                new Agent.Instruction("Reloaded external instruction."));

        assertThat(second.traceMetadata()).containsEntry("agentId", "reloaded-root-agent");
        verify(fixture.systemSpec).text("Reloaded external instruction.");
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionScope.Purpose.class, names = {
            "WORKFLOW_PLANNING", "EVALUATION", "WORKER", "SYNTHESIS",
            "RESPONSE_ONLY_RETRY", "COMPACTION"
    })
    void internalAgentExecutionUsesTheRunnerBoundInstruction(ExecutionScope.Purpose purpose) {
        Fixture fixture = new Fixture();
        fixture.responses(Flux.just(response("Internal result.")));
        List<Message> trustedHistory = purpose == ExecutionScope.Purpose.RESPONSE_ONLY_RETRY
                || purpose == ExecutionScope.Purpose.COMPACTION
                ? List.of()
                : List.of(new org.springframework.ai.chat.messages.SystemMessage(
                        "Internal Agent instruction."));

        AiChatExecutor.Result result = fixture.executor(null).execute(
                new AiChatExecutor.Context(request("Evaluate"),
                        trustedHistory,
                        new UserMessage("Evaluate"), fixture.requester,
                        fixture.recorder("request-1"), false, false,
                        AiChatExecutor.ToolPolicy.NONE, 0)
                        .withAgentIdentity("internal-agent", purpose),
                new Agent.Instruction("Internal Agent instruction."));

        assertThat(result.answer()).isEqualTo("Internal result.");
        verify(fixture.systemSpec).text("Internal Agent instruction.");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retainsPriorToolResultsAcrossMultipleApprovalWaves() {
        Fixture fixture = new Fixture();
        AiMutationToolGuard mutationGuard = mock(AiMutationToolGuard.class);
        AiMutationToolGuard.GuardedToolSession guardedSession =
                mock(AiMutationToolGuard.GuardedToolSession.class);
        AiMutationApprovalCoordinator approvals = mock(AiMutationApprovalCoordinator.class);
        org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle waitLifecycle =
                mock(org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle.class);
        AiPendingMutationApproval first = pending("approval-1", "update_a");
        AiPendingMutationApproval second = pending("approval-2", "update_b");
        when(guardedSession.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(guardedSession.executeApproved(any())).thenReturn(Optional.empty());
        when(guardedSession.pendingApprovals()).thenReturn(
                List.of(first), List.of(first), List.of(second), List.of(second), List.of());
        when(guardedSession.resolveApprovals(any(), any()))
                .thenReturn(List.of(new AiResolvedMutation(
                                "update_a", "{\"id\":1}", "result-one", true)),
                        List.of(new AiResolvedMutation(
                                "update_b", "{\"id\":2}", "result-two", true)));
        when(mutationGuard.session(any(), any(), any(), any(), any()))
                .thenReturn(guardedSession);
        when(approvals.awaitDecisions(
                any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Map.of("approval-1", new AiMutationApprovalResolution(
                                "approval-1", AiMutationApprovalResolution.Decision.APPROVE, "grant-1")),
                        Map.of("approval-2", new AiMutationApprovalResolution(
                                "approval-2", AiMutationApprovalResolution.Decision.APPROVE, "grant-2")));
        fixture.responses(Flux.just(response("Waiting for first approval.")),
                Flux.just(response("Waiting for second approval.")),
                Flux.just(response("Both updates completed.")));
        fixture.mcp(new ToolCallback[0], Set.of());
        AiTrajectoryRecorder recorder = fixture.recorder("request-1");
        ChatRequest request = request("Apply both updates");

        AiChatExecutor.Result result = execute(fixture.executor(mutationGuard, approvals),
                new AiChatExecutor.Context(request, List.of(),
                        new UserMessage(request.prompt()), fixture.requester, recorder,
                        true, false, AiChatExecutor.ToolPolicy.FULL, 1,
                        null, waitLifecycle));

        assertThat(result.answer()).isEqualTo("Both updates completed.");
        assertThat(result.traceMetadata())
                .containsEntry("approvalBarrierResolved", true)
                .containsEntry("approvalBarrierCount", 2)
                .containsEntry("approvedMutationCount", 2)
                .containsEntry("deniedMutationCount", 0);
        assertThat(recorder.pendingApprovalCount()).isZero();
        ArgumentCaptor<List<Message>> messageCalls = ArgumentCaptor.forClass(List.class);
        verify(fixture.requestSpec, times(3)).messages(messageCalls.capture());
        List<ToolResponseMessage.ToolResponse> retained = messageCalls.getAllValues().getLast().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .toList();
        assertThat(retained).extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("result-one", "result-two");
        assertThat(retained).allSatisfy(response ->
                assertThat(response.id()).startsWith("approved-"));
        verify(waitLifecycle, times(2)).suspendForApproval();
        verify(waitLifecycle, times(2)).resumeAfterApproval();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void countsAnApprovedButFailedMutationAsFailedRatherThanDenied() {
        Fixture fixture = new Fixture();
        AiMutationToolGuard mutationGuard = mock(AiMutationToolGuard.class);
        AiMutationToolGuard.GuardedToolSession guardedSession =
                mock(AiMutationToolGuard.GuardedToolSession.class);
        AiMutationApprovalCoordinator approvals = mock(AiMutationApprovalCoordinator.class);
        org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle waitLifecycle =
                mock(org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle.class);
        AiPendingMutationApproval pending = pending("approval-1", "delete_a");
        String failure = "{\"error\":\"" + AiMutationToolGuard.MUTATION_FAILED
                + "\",\"message\":\"It is still referenced by context scheme records.\"}";
        when(guardedSession.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(guardedSession.executeApproved(any())).thenReturn(Optional.empty());
        when(guardedSession.pendingApprovals()).thenReturn(
                List.of(pending), List.of(pending), List.of());
        when(guardedSession.resolveApprovals(any(), any())).thenReturn(List.of(
                new AiResolvedMutation("delete_a", "{\"id\":1}", failure, false)));
        when(mutationGuard.session(any(), any(), any(), any(), any()))
                .thenReturn(guardedSession);
        when(approvals.awaitDecisions(
                any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Map.of("approval-1", new AiMutationApprovalResolution(
                        "approval-1", AiMutationApprovalResolution.Decision.APPROVE, "grant-1")));
        fixture.responses(Flux.just(response("Waiting for approval.")),
                Flux.just(response("The deletion did not go through.")));
        fixture.mcp(new ToolCallback[0], Set.of());
        AiTrajectoryRecorder recorder = fixture.recorder("request-1");
        ChatRequest request = request("Delete it");

        AiChatExecutor.Result result = execute(fixture.executor(mutationGuard, approvals),
                new AiChatExecutor.Context(request, List.of(),
                        new UserMessage(request.prompt()), fixture.requester, recorder,
                        true, false, AiChatExecutor.ToolPolicy.FULL, 1,
                        null, waitLifecycle));

        assertThat(result.answer()).isEqualTo("The deletion did not go through.");
        assertThat(result.traceMetadata())
                .containsEntry("approvedMutationCount", 0)
                .containsEntry("deniedMutationCount", 0)
                .containsEntry("failedMutationCount", 1);
        ArgumentCaptor<List<Message>> messageCalls = ArgumentCaptor.forClass(List.class);
        verify(fixture.requestSpec, times(2)).messages(messageCalls.capture());
        assertThat(messageCalls.getAllValues().getLast().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData))
                .containsExactly(failure);
    }

    @Test
    void clearsSharedPendingApprovalEvidenceWhenWorkerResumeFails() {
        Fixture fixture = new Fixture();
        AiMutationToolGuard mutationGuard = mock(AiMutationToolGuard.class);
        AiMutationToolGuard.GuardedToolSession guardedSession =
                mock(AiMutationToolGuard.GuardedToolSession.class);
        AiMutationApprovalCoordinator approvals = mock(AiMutationApprovalCoordinator.class);
        org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle waitLifecycle =
                mock(org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle.class);
        AiPendingMutationApproval pending = pending("approval-1", "update_a");
        when(guardedSession.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(guardedSession.executeApproved(any())).thenReturn(Optional.empty());
        when(guardedSession.pendingApprovals()).thenReturn(List.of(pending), List.of(pending));
        when(mutationGuard.session(any(), any(), any(), any(), any()))
                .thenReturn(guardedSession);
        when(approvals.awaitDecisions(
                any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Map.of("approval-1", new AiMutationApprovalResolution(
                        "approval-1", AiMutationApprovalResolution.Decision.APPROVE, "grant-1")));
        doThrow(new CancellationException("resume failed"))
                .when(waitLifecycle).resumeAfterApproval();
        fixture.responses(Flux.just(response("Waiting for approval.")));
        fixture.mcp(new ToolCallback[0], Set.of());
        AiTrajectoryRecorder recorder = spy(fixture.recorder("request-1"));
        ChatRequest request = request("Apply update");

        assertThatThrownBy(() -> execute(fixture.executor(mutationGuard, approvals),
                new AiChatExecutor.Context(request, List.of(),
                        new UserMessage(request.prompt()), fixture.requester, recorder,
                        true, false, AiChatExecutor.ToolPolicy.FULL, 1,
                        null, waitLifecycle)))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("resume failed");

        verify(recorder).mutationApprovalsResolved(List.of(pending));
        verify(guardedSession, never()).resolveApprovals(any(), any());
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

        AiChatExecutor.Result result = execute(fixture.executorWithPolicies(mutationGuard,
                toolPolicies(request -> new ToolOutputGuardrail.Result.Allow(
                        request.output(), GuardrailDecision.of("tool-output", "1",
                                GuardrailDecision.Action.ALLOW))), allowModelInput()),
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
                                .isEqualTo("Inspect it"));
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

        AiChatExecutor.Result result = execute(fixture.executorWithPolicies(null,
                toolPolicies(request -> new ToolOutputGuardrail.Result.Allow(
                        request.output(), GuardrailDecision.of("tool-output", "1",
                                GuardrailDecision.Action.ALLOW))), allowModelInput()),
                new AiChatExecutor.Context(request("Verify it"), List.of(),
                        new UserMessage("Verify it"), fixture.requester,
                        fixture.recorder("request-1")));

        assertThat(result.answer()).isEqualTo("Verified: business context 7 exists.");
        verify(read).call(eq("{\"id\":7}"),
                any(org.springframework.ai.chat.model.ToolContext.class));
    }

    @Test
    void recordsOnlyTheOutputGuardedToolResult() {
        Fixture fixture = new Fixture();
        AtomicReference<ToolCallbackProvider> installedTools = fixture.captureInstalledTools();
        ToolCallback read = tool("get_business_context", "UNSAFE_RAW_SENTINEL");
        fixture.mcp(read, Set.of("get_business_context"));
        fixture.responses(Flux.defer(() -> {
            String result = installedTools.get().getToolCallbacks()[0].call("{\"id\":7}");
            assertThat(result).isEqualTo("{\"id\":7,\"name\":\"safe\"}");
            return Flux.just(response("Safe result summarized."));
        }));
        ToolGuardrailRegistry policies = toolPolicies(request ->
                new ToolOutputGuardrail.Result.Rewrite(
                        new org.oagi.score.gateway.http.api.ai_management.tool.AiTool.ToolResult(
                                "{\"id\":7,\"name\":\"safe\"}"),
                        GuardrailDecision.of("tool-output", "1",
                                GuardrailDecision.Action.REWRITE)));
        AiMutationToolGuard mutationGuard = new AiMutationToolGuard(
                mock(AiMutationConfirmationService.class), mock(AiRequestRegistry.class));
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository,
                new com.fasterxml.jackson.databind.ObjectMapper(), fixture.requester,
                "conversation-1", "request-1", ignored -> { });

        AiChatExecutor.Result result = execute(fixture.executorWithPolicies(
                mutationGuard, policies, allowModelInput()),
                new AiChatExecutor.Context(request("Inspect it"), List.of(),
                        new UserMessage("Inspect it"), fixture.requester, recorder));

        assertThat(result.answer()).isEqualTo("Safe result summarized.");
        ArgumentCaptor<org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(
                        org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep.class);
        verify(repository, org.mockito.Mockito.atLeastOnce())
                .append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).noneMatch(step ->
                java.util.Objects.toString(step.message(), "").contains("UNSAFE_RAW_SENTINEL"));
        assertThat(steps.getAllValues()).anyMatch(step ->
                java.util.Objects.toString(step.message(), "").contains("\"name\":\"safe\""));
    }

    @Test
    void modelGuardrailReceivesAttachmentBytesAndRefusesBeforePromptInvocation() {
        Fixture fixture = new Fixture();
        byte[] attachmentBytes = "model-policy-image".getBytes(StandardCharsets.UTF_8);
        AgentInputGuardrail attachmentPolicy = request -> {
            assertThat(request.input().attachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.name()).isEqualTo("model.png");
                assertThat(attachment.mediaType()).isEqualTo("image/png");
                assertThat(attachment.data()).isEqualTo(attachmentBytes);
            });
            assertThat(request.assembledMessages()).anySatisfy(message ->
                    assertThat(message).isInstanceOfSatisfying(
                            org.oagi.score.gateway.http.api.ai_management.agent.AiMessage.User.class,
                            user -> assertThat(user.attachments()).isNotEmpty()));
            return new AgentInputGuardrail.Result.Refuse(new GuardrailRefusal(
                    GuardrailDecision.of("model-attachment-policy", "1",
                            GuardrailDecision.Action.REFUSE),
                    "ATTACHMENT_BLOCKED", "ai.policy.refused"));
        };
        Media media = Media.builder().mimeType(MimeType.valueOf("image/png"))
                .data(attachmentBytes).name("model.png").build();
        UserMessage user = UserMessage.builder().text("Inspect it")
                .media(List.of(media)).build();
        AiChatExecutor executor = fixture.executorWithPolicies(null,
                toolPolicies(request -> new ToolOutputGuardrail.Result.Allow(
                        request.output(), GuardrailDecision.of("tool-output", "1",
                        GuardrailDecision.Action.ALLOW))),
                new AgentInputGuardrailChain(List.of(attachmentPolicy)));

        assertThatThrownBy(() -> execute(executor, new AiChatExecutor.Context(
                request("Inspect it"), List.of(), user, fixture.requester,
                fixture.recorder("request-1"), false, false,
                AiChatExecutor.ToolPolicy.NONE, 0)
                .withAgentIdentity("test-root-agent",
                        ExecutionScope.Purpose.USER_RESPONSE)))
                .isInstanceOfSatisfying(AgentInputRefusedException.class,
                        failure -> assertThat(failure.agentId())
                                .contains(new Agent.AgentId("test-root-agent")));

        verify(fixture.client, never()).prompt();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redactsSecretsFromRequestScopedPageContextBeforePromptMessages() {
        Fixture fixture = new Fixture();
        fixture.responses(Flux.just(response("Safe response.")));
        ChatRequest request = new ChatRequest("Inspect", "request-1", null,
                "conversation-1", "api_key=raw-page-secret", List.of(), null,
                "configured-model", "high", "ask");

        AiChatExecutor.Result result = execute(fixture.executorWithPolicies(null,
                toolPolicies(guarded -> new ToolOutputGuardrail.Result.Allow(
                        guarded.output(), GuardrailDecision.of("tool-output", "1",
                        GuardrailDecision.Action.ALLOW))),
                allowModelInput()), new AiChatExecutor.Context(
                request, List.of(), new UserMessage("Inspect"), fixture.requester,
                fixture.recorder("request-1"), false, false,
                AiChatExecutor.ToolPolicy.NONE, 0)
                .withAgentIdentity("unresolved-root-agent",
                        ExecutionScope.Purpose.USER_RESPONSE));

        assertThat(result.answer()).isEqualTo("Safe response.");
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(fixture.requestSpec).messages(messages.capture());
        assertThat(messages.getValue()).extracting(Message::getText)
                .noneMatch(text -> text != null && text.contains("raw-page-secret"))
                .anyMatch(text -> text != null && text.contains("api_key=[REDACTED]"));
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

        AiChatExecutor.Result result = execute(fixture.executorWithPolicies(null,
                toolPolicies(request -> new ToolOutputGuardrail.Result.Allow(
                        request.output(), GuardrailDecision.of("tool-output", "1",
                                GuardrailDecision.Action.ALLOW))), allowModelInput()),
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

    private static AiChatExecutor.Result execute(AiChatExecutor executor,
                                                 AiChatExecutor.Context context) {
        return executor.execute(context, TEST_INSTRUCTION);
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

    private static AgentInputGuardrailChain allowModelInput() {
        return new AgentInputGuardrailChain(List.of(request ->
                new AgentInputGuardrail.Result.Allow(GuardrailDecision.of(
                        "model-input", "1", GuardrailDecision.Action.ALLOW))));
    }

    private static ToolGuardrailRegistry toolPolicies(ToolOutputGuardrail output) {
        ToolInputGuardrail input = request -> new ToolInputGuardrail.Result.Allow(
                request.arguments(), GuardrailDecision.of("tool-input", "1",
                GuardrailDecision.Action.ALLOW));
        return new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(input), List.of(output)), Map.of());
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
        private final ChatClient.PromptSystemSpec systemSpec =
                mock(ChatClient.PromptSystemSpec.class);
        private final ScoreUser requester = mock(ScoreUser.class);
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
            when(systemSpec.text(anyString())).thenReturn(systemSpec);
            when(requestSpec.system(any(Consumer.class))).thenAnswer(invocation -> {
                ((Consumer<ChatClient.PromptSystemSpec>) invocation.getArgument(0))
                        .accept(systemSpec);
                return requestSpec;
            });
            when(requestSpec.messages(anyList())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.stream()).thenReturn(responseSpec);
        }

        private AiChatExecutor executor(AiMutationToolGuard mutationGuard) {
            return new AiChatExecutor(models, mcpClients, toolSearchAdvisor,
                    mutationGuard, null, null, optionsFactory);
        }

        private AiChatExecutor executor(
                AiMutationToolGuard mutationGuard,
                AiMutationApprovalCoordinator approvalCoordinator) {
            return new AiChatExecutor(models, mcpClients, toolSearchAdvisor,
                    mutationGuard, null, null, optionsFactory, approvalCoordinator);
        }

        private AiChatExecutor executorWithPolicies(
                AiMutationToolGuard mutationGuard,
                ToolGuardrailRegistry toolGuardrails,
                AgentInputGuardrailChain modelInputGuardrails) {
            return new AiChatExecutor(models, mcpClients, toolSearchAdvisor,
                    mutationGuard, null, null, optionsFactory, null, toolGuardrails,
                    new SpringAiCallbackToolSetAdapter(), new SpringAiToolAdapter(),
                    modelInputGuardrails, null, null);
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

        @SafeVarargs
        private final void responses(Flux<ChatResponse>... responses) {
            when(responseSpec.chatResponse()).thenReturn(
                    responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
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

    private AiPendingMutationApproval pending(String id, String toolName) {
        return new AiPendingMutationApproval(new AiMutationConfirmationNotice(
                id, "REQUESTED", Instant.now().plusSeconds(60), toolName, "{\"id\":1}"),
                toolName, "{\"id\":1}");
    }
}
