package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationPermissionMode;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationRisk;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.MutationConfirmation;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiCallbackToolSetAdapter;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiToolAdapter;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMutationToolGuardTest {

    /** Stands in for the tool names the MCP server annotates with readOnlyHint=true. */
    private static final Set<String> SERVER_READ_ONLY_TOOLS = Set.of(
            "get_business_context", "get_top_level_asbiep_list", "get_xbt", "who_am_i");

    private final AiMutationConfirmationService confirmations = mock(AiMutationConfirmationService.class);
    private final AiRequestRegistry requests = mock(AiRequestRegistry.class);
    private final RecordingOwnership ownership = new RecordingOwnership();
    private final AiMutationToolGuard guard = new AiMutationToolGuard(confirmations, requests, ownership);
    private final ScoreUser requester = mock(ScoreUser.class);
    private final ChatRequest request = new ChatRequest("change it", "request-1", null,
            "conversation-1", null, List.of(), null, "model", "high", null);

    @Test
    void permitsReadToolsWithoutConsultingMutationState() {
        ToolCallback delegate = tool("get_business_context", "read-result");
        ToolCallback guarded = guarded(delegate, ignored -> {});

        assertThat(guarded.call("{}", new ToolContext(Map.of()))).isEqualTo("read-result");
        verify(confirmations, never()).authorize(any(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void normalizesNumericReadIdsBeforeCallingTheMcpTool() {
        ToolCallback delegate = tool("get_business_context", "read-result", """
                {"type":"object","properties":{"biz_ctx_id":{"type":"integer"}},
                 "required":["biz_ctx_id"]}
                """);
        ToolCallback guarded = guarded(delegate, ignored -> {});

        assertThat(guarded.call("{\"biz_ctx_id\":\"18\"}", new ToolContext(Map.of())))
                .isEqualTo("read-result");

        verify(delegate).call(org.mockito.ArgumentMatchers.eq("{\"biz_ctx_id\":18}"),
                any(ToolContext.class));
    }

    @Test
    void blocksMutationAndEmitsOnlyOneOwnerSafeNoticeUntilApproved() {
        var notice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "delete_business_context", "{\"id\":1}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.required(notice));
        ToolCallback delegate = tool("delete_business_context", "deleted");
        List<AiMutationConfirmationNotice> notices = new ArrayList<>();
        ToolCallback guarded = guarded(delegate, notices::add);

        assertThat(guarded.call("{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
        assertThat(guarded.call("{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
        assertThat(notices).containsExactly(notice);
        verify(delegate, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void resumesEveryApprovedInvocationInTheSameBatchWithoutReplayingTheUserPrompt() {
        AiMutationConfirmationNotice firstNotice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "update_business_context", "{\"id\":1}");
        AiMutationConfirmationNotice secondNotice = new AiMutationConfirmationNotice(
                "confirmation-2", "REQUESTED", Instant.now().plusSeconds(60),
                "delete_business_context", "{\"id\":2}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(3) != null
                        ? AiMutationAuthorization.permitted()
                        : AiMutationAuthorization.required(
                                "update_business_context".equals(invocation.getArgument(4))
                                        ? firstNotice : secondNotice));
        when(confirmations.argumentsDigest(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "\n" + invocation.getArgument(1));
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback update = tool("update_business_context", "updated");
        ToolCallback delete = tool("delete_business_context", "deleted");
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                request, requester, ignored -> {}, () -> new ToolCallback[]{update, delete},
                SERVER_READ_ONLY_TOOLS);

        session.getToolCallbacks()[0].call("{\"id\":1}", new ToolContext(Map.of()));
        session.getToolCallbacks()[1].call("{\"id\":2}", new ToolContext(Map.of()));
        assertThat(session.pendingApprovals()).hasSize(2);

        var resolved = session.resolveApprovals(session, Map.of(
                "confirmation-1", new AiMutationApprovalResolution(
                        "confirmation-1", AiMutationApprovalResolution.Decision.APPROVE, "grant-1"),
                "confirmation-2", new AiMutationApprovalResolution(
                        "confirmation-2", AiMutationApprovalResolution.Decision.APPROVE, "grant-2")));

        assertThat(resolved).hasSize(2).allMatch(result -> result.executed());
        assertThat(session.pendingApprovals()).isEmpty();
        assertThat(session.completedMutations()).extracting(AiApprovedExecution::toolName)
                .containsExactly("update_business_context", "delete_business_context");
        assertThat(session.getToolCallbacks()[0].call(
                "{\"id\":1}", new ToolContext(Map.of()))).isEqualTo("updated");
        assertThat(session.getToolCallbacks()[1].call(
                "{\"id\":2}", new ToolContext(Map.of()))).isEqualTo("deleted");
        verify(update, times(1)).call(
                org.mockito.ArgumentMatchers.eq("{\"id\":1}"), any(ToolContext.class));
        verify(delete, times(1)).call(
                org.mockito.ArgumentMatchers.eq("{\"id\":2}"), any(ToolContext.class));
    }

    @Test
    void coreGatewayOwnsAuthorizationAndResumesTheExactRetainedSession() {
        AiMutationConfirmationNotice notice = new AiMutationConfirmationNotice(
                "confirmation-core", "REQUESTED", Instant.now().plusSeconds(60),
                "update_business_context", "{\"id\":18}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(3) != null
                        ? AiMutationAuthorization.permitted()
                        : AiMutationAuthorization.required(notice));
        when(confirmations.argumentsDigest(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "\n" + invocation.getArgument(1));
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback update = tool("update_business_context", "updated", """
                {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}
                """);
        ToolCallbackProvider provider = () -> new ToolCallback[]{update};
        AiMutationToolGuard.GuardedToolSession session = guard.authorizationSession(
                request, requester, ignored -> {}, provider, SERVER_READ_ONLY_TOOLS);
        var coreTools = new SpringAiCallbackToolSetAdapter().adapt(provider, SERVER_READ_ONLY_TOOLS);
        ToolGuardrailRegistry guardrails = passThroughGuardrails();
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1",
                1, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ToolExecutionGateway gateway = new ToolExecutionGateway(coreTools, guardrails,
                List.of(session), ignored -> {}, ExecutionObserver.noop(),
                new ExecutionState(), 4096);
        ToolCallbackProvider callbacks = new SpringAiToolAdapter().adapt(coreTools, gateway, scope);

        assertThat(callbacks.getToolCallbacks()[0].call("{\"id\":18}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
        assertThat(session.pendingApprovals()).singleElement()
                .extracting(pending -> pending.notice().confirmationRequestId())
                .isEqualTo("confirmation-core");

        var resolved = session.resolveApprovals(callbacks, Map.of(
                "confirmation-core", new AiMutationApprovalResolution(
                        "confirmation-core", AiMutationApprovalResolution.Decision.APPROVE,
                        "grant-core")));

        assertThat(resolved).singleElement().satisfies(result -> {
            assertThat(result.executed()).isTrue();
            assertThat(result.result()).isEqualTo("updated");
        });
        assertThat(session.completedMutations()).singleElement()
                .extracting(AiApprovedExecution::arguments)
                .isEqualTo("{\"id\":18}");
        verify(update).call(org.mockito.ArgumentMatchers.eq("{\"id\":18}"),
                any(ToolContext.class));
        verify(requests).mutationStarted("request-1");
        verify(requests).mutationFinished("request-1");
    }

    @Test
    void retainedMutationReadBackContainsOnlyTheOutputGuardedResult() {
        ChatRequest fullAccess = request("full_access", null);
        when(confirmations.argumentsDigest(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "\n" + invocation.getArgument(1));
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback update = tool("update_business_context", "UNSAFE_RAW_MUTATION_RESULT");
        ToolCallbackProvider provider = () -> new ToolCallback[]{update};
        AiMutationToolGuard.GuardedToolSession session = guard.authorizationSession(
                fullAccess, requester, ignored -> { }, provider, SERVER_READ_ONLY_TOOLS);
        var coreTools = new SpringAiCallbackToolSetAdapter().adapt(provider, SERVER_READ_ONLY_TOOLS);
        ToolInputGuardrail input = guarded -> new ToolInputGuardrail.Result.Allow(
                guarded.arguments(), GuardrailDecision.of("test-input", "1",
                GuardrailDecision.Action.ALLOW));
        ToolOutputGuardrail output = guarded -> new ToolOutputGuardrail.Result.Rewrite(
                new org.oagi.score.gateway.http.api.ai_management.tool.AiTool.ToolResult(
                        "{\"status\":\"safe\"}"),
                GuardrailDecision.of("test-output", "1", GuardrailDecision.Action.REWRITE));
        ToolGuardrailRegistry guardrails = new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(input), List.of(output)), Map.of());
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1",
                1, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ToolExecutionGateway gateway = new ToolExecutionGateway(coreTools, guardrails,
                List.of(session), ignored -> { }, ExecutionObserver.noop(),
                new ExecutionState(), 4096);
        ToolCallback callback = new SpringAiToolAdapter().adapt(coreTools, gateway, scope)
                .getToolCallbacks()[0];

        assertThat(callback.call("{\"id\":18}", new ToolContext(Map.of())))
                .isEqualTo("{\"status\":\"safe\"}");
        assertThat(session.completedMutations()).singleElement().satisfies(execution ->
                assertThat(execution.result()).isEqualTo("{\"status\":\"safe\"}"));
        assertThat(callback.call("{\"id\":18}", new ToolContext(Map.of())))
                .isEqualTo("{\"status\":\"safe\"}");
        verify(update, times(1)).call(org.mockito.ArgumentMatchers.eq("{\"id\":18}"),
                any(ToolContext.class));
    }

    @Test
    void cachesAnExactDenialSoTheModelCannotOpenTheSameApprovalAgain() {
        AiMutationConfirmationNotice notice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "delete_business_context", "{\"id\":1}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.required(notice));
        when(confirmations.argumentsDigest(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "\n" + invocation.getArgument(1));
        ToolCallback delete = tool("delete_business_context", "deleted");
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                request, requester, ignored -> {}, () -> new ToolCallback[]{delete},
                SERVER_READ_ONLY_TOOLS);

        assertThat(session.getToolCallbacks()[0].call(
                "{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
        session.resolveApprovals(session, Map.of(
                "confirmation-1", new AiMutationApprovalResolution(
                        "confirmation-1", AiMutationApprovalResolution.Decision.DENY, null)));

        assertThat(session.getToolCallbacks()[0].call(
                "{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_DENIED");
        assertThat(session.pendingApprovals()).isEmpty();
        verify(confirmations, times(1)).authorize(
                any(), anyString(), anyString(), any(), anyString(), anyString());
        verify(delete, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void reportsWhyAnApprovedMutationFailedInsteadOfEndingTheTurn() {
        AiMutationConfirmationNotice notice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "delete_business_context", "{\"id\":1}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(3) != null
                        ? AiMutationAuthorization.permitted()
                        : AiMutationAuthorization.required(notice));
        when(confirmations.argumentsDigest(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "\n" + invocation.getArgument(1));
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback delete = failingTool("delete_business_context",
                "Unable to delete business context 1. It is still referenced by"
                        + " business context value records.");
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                request, requester, ignored -> {}, () -> new ToolCallback[]{delete},
                SERVER_READ_ONLY_TOOLS);

        assertThat(session.getToolCallbacks()[0].call("{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
        var resolved = session.resolveApprovals(session, Map.of(
                "confirmation-1", new AiMutationApprovalResolution(
                        "confirmation-1", AiMutationApprovalResolution.Decision.APPROVE, "grant-1")));

        assertThat(resolved).singleElement().satisfies(result -> {
            assertThat(result.toolName()).isEqualTo("delete_business_context");
            assertThat(result.executed()).isFalse();
            assertThat(result.result())
                    .contains("MUTATION_FAILED")
                    .contains("It is still referenced by business context value records.");
        });
        assertThat(session.completedMutations()).isEmpty();
        assertThat(session.mutationCompleted()).isFalse();
        assertThat(session.pendingApprovals()).isEmpty();
        assertThat(session.confirmationRequired()).isFalse();
        // The failure is not cached as a decision, so a retry asks the user again.
        assertThat(session.getToolCallbacks()[0].call("{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
    }

    @Test
    void refusesToStartAnApprovedMutationAfterCancellationWinsTheRace() {
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.permitted());
        when(requests.mutationStarted("request-1")).thenReturn(false);
        ToolCallback delegate = tool("update_business_context", "updated");

        assertThat(guarded(delegate, ignored -> {}).call("{}", new ToolContext(Map.of())))
                .contains("REQUEST_STOPPING");
        verify(delegate, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void bracketsAnApprovedMutationForCancellationReconciliation() {
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.permitted());
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback delegate = tool("create_business_context", "created");

        assertThat(guarded(delegate, ignored -> {}).call("{}", new ToolContext(Map.of())))
                .isEqualTo("created");
        verify(requests).mutationStarted("request-1");
        verify(requests).mutationFinished("request-1");
    }

    @Test
    void fullAccessRunsMutationsWithoutCreatingApprovalRequests() {
        ChatRequest fullAccess = request("full_access", null);
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback delegate = tool("delete_business_context", "deleted");

        assertThat(guarded(fullAccess, delegate, ignored -> {})
                .call("{\"id\":1}", new ToolContext(Map.of())))
                .isEqualTo("deleted");

        verify(confirmations, never()).authorize(any(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void approveForMeAllowsAdditiveChangesButStillAsksForDestructiveOnes() {
        ChatRequest automatic = request("auto", null);
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback create = tool("create_business_context", "created");

        assertThat(guarded(automatic, create, ignored -> {})
                .call("{}", new ToolContext(Map.of())))
                .isEqualTo("created");

        var notice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "delete_business_context", "{\"id\":1}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.eq("delete_business_context"), anyString()))
                .thenReturn(AiMutationAuthorization.required(notice));
        assertThat(guarded(automatic, tool("delete_business_context", "deleted"), ignored -> {})
                .call("{\"id\":1}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
    }

    @Test
    void executesTheExactApprovedInvocationBeforeReturningControlToTheModel() {
        String arguments = "{\"name\":\"Approved\"}";
        ChatRequest approved = request("ask", new MutationConfirmation(
                "confirmation-1", "grant", "create_business_context", arguments));
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.permitted());
        when(confirmations.argumentsDigest(anyString(), anyString())).thenReturn("same-digest");
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback delegate = tool("create_business_context", "created");
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                approved, requester, ignored -> {}, () -> new ToolCallback[]{delegate},
                SERVER_READ_ONLY_TOOLS);

        AiApprovedExecution execution = session.executeApproved(session).orElseThrow();

        assertThat(execution.toolName()).isEqualTo("create_business_context");
        assertThat(execution.arguments()).isEqualTo(arguments);
        assertThat(execution.result()).isEqualTo("created");
        assertThat(session.mutationCompleted()).isTrue();
        assertThat(session.getToolCallbacks()[0].call(arguments, new ToolContext(Map.of())))
                .isEqualTo("created");
        verify(delegate).call(org.mockito.ArgumentMatchers.eq(arguments), any(ToolContext.class));
    }

    @Test
    void letsTheModelTranslateARevisedApprovalButDoesNotEagerlyRunTheOldArguments() {
        MutationConfirmation revision = new MutationConfirmation(
                "confirmation-1", "grant", "create_business_context", null,
                "REVISED", "Use the name Revised");
        ChatRequest approved = request("ask", revision);
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.permitted());
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ToolCallback delegate = tool("create_business_context", "created");
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                approved, requester, ignored -> {}, () -> new ToolCallback[]{delegate},
                SERVER_READ_ONLY_TOOLS);

        assertThat(session.executeApproved(session)).isEmpty();
        verify(delegate, never()).call(anyString(), any(ToolContext.class));

        assertThat(session.getToolCallbacks()[0].call(
                "{\"name\":\"Revised\"}", new ToolContext(Map.of())))
                .isEqualTo("created");
        verify(delegate).call(org.mockito.ArgumentMatchers.eq("{\"name\":\"Revised\"}"),
                any(ToolContext.class));
    }

    @Test
    void tracksWhetherAReadSucceededAfterTheLastMutation() {
        ChatRequest fullAccess = request("full_access", null);
        when(requests.mutationStarted("request-1")).thenReturn(true);
        AiMutationToolGuard.GuardedToolSession session = guard.session(
                fullAccess, requester, ignored -> {}, () -> new ToolCallback[]{
                        tool("create_business_context", "created"),
                        tool("get_business_context", "read")},
                SERVER_READ_ONLY_TOOLS);

        session.getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));
        assertThat(session.readAfterLastMutation()).isFalse();
        assertThat(session.completedMutations()).singleElement()
                .extracting(AiApprovedExecution::toolName)
                .isEqualTo("create_business_context");

        session.getToolCallbacks()[1].call("{}", new ToolContext(Map.of()));
        assertThat(session.readAfterLastMutation()).isTrue();
    }

    @Test
    void treatsOnlyServerAnnotatedReadOnlyToolsAsReadsAndFailsClosedForEveryOtherName() {
        assertThat(List.of("create_x", "update_x", "delete_x", "add_x", "remove_x",
                "assign_x", "unassign_x", "transfer_x", "discard_x", "publish_x",
                "copy_x", "move_x", "uplift_x", "reuse_x", "set_x", "reset_x",
                "replace_x", "import_x", "upload_x", "change_x", "cancel_x",
                "revise_or_amend_x", "search_x", "future_unknown_tool",
                "get_and_delete_business_context", "GET_BUSINESS_CONTEXT", "get_x"))
                .allMatch(name -> AiMutationToolGuard.isMutation(name, SERVER_READ_ONLY_TOOLS));
        assertThat(List.of("get_business_context", "get_top_level_asbiep_list", "get_xbt", "who_am_i"))
                .noneMatch(name -> AiMutationToolGuard.isMutation(name, SERVER_READ_ONLY_TOOLS));
        assertThat(AiMutationToolGuard.isMutation(null, SERVER_READ_ONLY_TOOLS)).isTrue();
        assertThat(AiMutationToolGuard.isMutation("get_business_context", null)).isTrue();
        assertThat(AiMutationToolGuard.isMutation("get_business_context", Set.of())).isTrue();
    }

    @Test
    void readOnlySpecialistProviderDropsEveryMutationAndUnknownTool() {
        ToolCallback read = tool("get_business_context", "read");
        ToolCallback mutation = tool("create_business_context", "created");
        ToolCallback unknown = tool("future_unknown_tool", "unsafe");

        ToolCallbackProvider provider = guard.readOnly(
                () -> new ToolCallback[]{mutation, read, unknown}, SERVER_READ_ONLY_TOOLS);

        assertThat(provider.getToolCallbacks()).extracting(callback ->
                callback.getToolDefinition().name()).containsExactly("get_business_context");
        verify(mutation, never()).call(anyString(), any(ToolContext.class));
        verify(unknown, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void permissionModesFailClosedAndKeepUnsafeChangesOutOfAutomaticMode() {
        assertThat(AiMutationPermissionMode.resolve(null)).isEqualTo(AiMutationPermissionMode.ASK);
        AiMutationPermissionMode automatic = AiMutationPermissionMode.resolve("auto");
        assertThat(automatic.automaticallyAllows(AiMutationRisk.UNRESTRICTED)).isTrue();
        assertThat(automatic.automaticallyAllows(AiMutationRisk.OWNER_SCOPED)).isFalse();
        assertThat(automatic.requiresOwnershipCheck(AiMutationRisk.OWNER_SCOPED)).isTrue();
        assertThat(automatic.automaticallyAllows(AiMutationRisk.ALWAYS_CONFIRM)).isFalse();
        assertThat(automatic.requiresOwnershipCheck(AiMutationRisk.ALWAYS_CONFIRM)).isFalse();
        assertThat(automatic.assistantPolicy())
                .isEqualTo("auto: creating new data runs without approval, and changing data the"
                        + " user owns runs without approval; changing data owned by somebody else"
                        + " requires approval, and so does every deletion, discard, cancellation,"
                        + " removal, state change, and ownership transfer.");
        AiMutationPermissionMode ask = AiMutationPermissionMode.resolve("ask");
        assertThat(ask.automaticallyAllows(AiMutationRisk.UNRESTRICTED)).isFalse();
        assertThat(ask.requiresOwnershipCheck(AiMutationRisk.OWNER_SCOPED)).isFalse();
        assertThat(ask.assistantPolicy())
                .isEqualTo("ask: every data-changing tool call requires explicit user approval.");
        AiMutationPermissionMode fullAccess = AiMutationPermissionMode.resolve("full_access");
        assertThat(fullAccess.automaticallyAllows(AiMutationRisk.ALWAYS_CONFIRM)).isTrue();
        assertThat(fullAccess.assistantPolicy())
                .isEqualTo("full_access: data-changing tool calls run without approval.");
        assertThatThrownBy(() -> AiMutationPermissionMode.resolve("unsafe-unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void automaticModeRunsACreateAndTheFollowUpChangeToTheDataTheRequesterOwns() {
        when(requests.mutationStarted("request-1")).thenReturn(true);
        ownership.owned = true;
        ChatRequest automatic = request("auto", null);
        ToolCallback create = tool("create_business_context", "{\"biz_ctx_id\":7}");
        ToolCallback update = tool("update_business_context", "{\"biz_ctx_id\":7}");

        assertThat(guarded(automatic, create, ignored -> {})
                .call("{\"name\":\"US Retail\"}", new ToolContext(Map.of())))
                .isEqualTo("{\"biz_ctx_id\":7}");
        assertThat(guarded(automatic, update, ignored -> {})
                .call("{\"biz_ctx_id\":7}", new ToolContext(Map.of())))
                .isEqualTo("{\"biz_ctx_id\":7}");

        verify(confirmations, never()).authorize(any(), anyString(), anyString(), any(),
                anyString(), anyString());
        assertThat(ownership.calls).containsExactly("update_business_context {\"biz_ctx_id\":7}");
    }

    @Test
    void automaticModeAsksBeforeChangingDataTheRequesterDoesNotOwn() {
        var notice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "update_business_context", "{\"biz_ctx_id\":7}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.required(notice));
        ownership.owned = false;
        ToolCallback update = tool("update_business_context", "updated");

        assertThat(guarded(request("auto", null), update, ignored -> {})
                .call("{\"biz_ctx_id\":7}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");

        verify(update, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void automaticModeAsksBeforeDeletingAndBeforeUnclassifiedChangesEvenForTheOwner() {
        var notice = new AiMutationConfirmationNotice(
                "confirmation-1", "REQUESTED", Instant.now().plusSeconds(60),
                "delete_business_context", "{\"biz_ctx_id\":7}");
        when(confirmations.authorize(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(AiMutationAuthorization.required(notice));
        ownership.owned = true;
        ToolCallback delete = tool("delete_business_context", "deleted");
        ToolCallback unclassified = tool("future_mutation", "changed");

        assertThat(guarded(request("auto", null), delete, ignored -> {})
                .call("{\"biz_ctx_id\":7}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");
        assertThat(guarded(request("auto", null), unclassified, ignored -> {})
                .call("{}", new ToolContext(Map.of())))
                .contains("MUTATION_CONFIRMATION_REQUIRED");

        verify(delete, never()).call(anyString(), any(ToolContext.class));
        verify(unclassified, never()).call(anyString(), any(ToolContext.class));
        assertThat(ownership.calls).isEmpty();
    }

    private ToolCallback guarded(ToolCallback callback,
                                 java.util.function.Consumer<AiMutationConfirmationNotice> notices) {
        return guarded(request, callback, notices);
    }

    private ToolCallback guarded(ChatRequest chatRequest, ToolCallback callback,
                                 java.util.function.Consumer<AiMutationConfirmationNotice> notices) {
        ToolCallbackProvider provider = guard.guard(chatRequest, requester, notices,
                () -> new ToolCallback[]{callback}, SERVER_READ_ONLY_TOOLS);
        return provider.getToolCallbacks()[0];
    }

    private ChatRequest request(String permissionMode, MutationConfirmation confirmation) {
        return new ChatRequest("change it", "request-1", null, "conversation-1",
                null, List.of(), confirmation, "model", "high", permissionMode);
    }

    private ToolCallback tool(String name, String result) {
        return tool(name, result, "{\"type\":\"object\"}");
    }

    private ToolCallback failingTool(String name, String failure) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class))).thenThrow(
                new ToolExecutionException(definition, new IllegalStateException(failure)));
        return callback;
    }

    private ToolCallback tool(String name, String result, String inputSchema) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema(inputSchema).build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn(result);
        return callback;
    }

    /** Stands in for the database-backed ownership check and records what the guard asked about. */
    private static final class RecordingOwnership implements AiMutationOwnershipPolicy {
        private final List<String> calls = new ArrayList<>();
        private boolean owned;

        @Override
        public boolean requesterOwnsTarget(ScoreUser requester, String toolName, String arguments) {
            calls.add(toolName + " " + arguments);
            return owned;
        }
    }

    private ToolGuardrailRegistry passThroughGuardrails() {
        ToolInputGuardrail input = guarded -> new ToolInputGuardrail.Result.Allow(
                guarded.arguments(), GuardrailDecision.of("test-input", "1",
                GuardrailDecision.Action.ALLOW));
        ToolOutputGuardrail output = guarded -> new ToolOutputGuardrail.Result.Allow(
                guarded.output(), GuardrailDecision.of("test-output", "1",
                GuardrailDecision.Action.ALLOW));
        return new ToolGuardrailRegistry(new ToolGuardrailRegistry.Set(
                List.of(input), List.of(output)), Map.of());
    }
}
