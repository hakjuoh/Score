package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationPermissionMode;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.MutationConfirmation;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMutationToolGuardTest {

    /** Stands in for the tool names the MCP server annotates with readOnlyHint=true. */
    private static final Set<String> SERVER_READ_ONLY_TOOLS = Set.of(
            "get_business_context", "get_top_level_asbiep_list", "get_xbt", "who_am_i");

    private final AiMutationConfirmationService confirmations = mock(AiMutationConfirmationService.class);
    private final AiRequestRegistry requests = mock(AiRequestRegistry.class);
    private final AiMutationToolGuard guard = new AiMutationToolGuard(confirmations, requests);
    private final ScoreUser requester = mock(ScoreUser.class);
    private final ChatRequest request = new ChatRequest("change it", "request-1", null,
            "conversation-1", null, List.of(), null, "model", "high", "default", Map.of());

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
        assertThat(AiMutationPermissionMode.resolve("auto").automaticallyAllows("create_acc")).isTrue();
        assertThat(AiMutationPermissionMode.resolve("auto").automaticallyAllows("delete_business_context"))
                .isFalse();
        assertThat(AiMutationPermissionMode.resolve("full_access").automaticallyAllows("future_mutation"))
                .isTrue();
        assertThatThrownBy(() -> AiMutationPermissionMode.resolve("unsafe-unknown"))
                .isInstanceOf(IllegalArgumentException.class);
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
                null, List.of(), confirmation, "model", "high", "default", Map.of(), permissionMode);
    }

    private ToolCallback tool(String name, String result) {
        return tool(name, result, "{\"type\":\"object\"}");
    }

    private ToolCallback tool(String name, String result, String inputSchema) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema(inputSchema).build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn(result);
        return callback;
    }
}
