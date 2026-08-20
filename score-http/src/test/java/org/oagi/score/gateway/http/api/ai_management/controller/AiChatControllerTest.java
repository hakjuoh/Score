package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancelRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiElicitationDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeConfirmationDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChangeReadBackException;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.AiSharedStateUnavailableException;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.oagi.score.gateway.http.configuration.websocket.WebSocketSessionUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.AuthenticatedPrincipal;

import java.math.BigInteger;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatControllerTest {

    private final ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
            null, false, List.of());
    private final AuthenticatedPrincipal principal = mock(AuthenticatedPrincipal.class);
    private final ChatService chatService = mock(ChatService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final WebSocketSessionUserResolver webSocketUsers = mock(WebSocketSessionUserResolver.class);

    @Test
    void terminalizesTheRequestWhenTheExecutorRejectsSubmission() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiChatController controller = controller(registry, new ScoreAiProperties(),
                ignored -> { throw new RejectedExecutionException("executor closed"); });
        ChatRequest request = request("request-rejected", "conversation-rejected");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> controller.chat(principal, request))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("executor closed");

        assertThat(registry.status("request-rejected", user).status()).isEqualTo("FAILED");
        verify(chatService).recordFailure(any(ChatRequest.class), eq(user),
                any(String.class), eq(RejectedExecutionException.class.getName()), anyLong());
    }

    @Test
    void closesTheTurnWithTheOriginalFailureWhenRejectedRequestSettlementAlsoFails() {
        AiRequestRegistry registry = spy(new AiRequestRegistry());
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.Turn turn = mock(ScoreAiObservability.Turn.class);
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        when(observability.startTurn(any(ChatRequest.class), eq(user), anyLong(),
                nullable(String.class), nullable(String.class))).thenReturn(turn);
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers, registry,
                mock(AiChangeConfirmationService.class), mock(AiElicitationService.class),
                approvals, new ScoreAiProperties(), observability,
                Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        IllegalStateException transportFailure = new IllegalStateException("socket unavailable");
        AiSharedStateUnavailableException settlementFailure =
                new AiSharedStateUnavailableException("settlement unavailable");
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup unavailable");
        IllegalStateException rejectionFailure = new IllegalStateException("rejection unavailable");
        IllegalStateException persistenceFailure = new IllegalStateException("persistence unavailable");
        doThrow(transportFailure)
                .when(messagingTemplate).convertAndSendToUser(
                        eq("tester"), eq("/queue/ai/chat/request-send-failed"),
                        any(AiChatSocketEvent.class));
        doThrow(settlementFailure).when(registry).finish(
                any(AiRequestRegistry.Entry.class), eq(transportFailure));
        doThrow(cleanupFailure).when(approvals).cancelRequest("request-send-failed");
        doThrow(rejectionFailure).when(turn).admissionRejected("transport_send_failed");
        doThrow(persistenceFailure).when(chatService).recordFailure(any(ChatRequest.class), eq(user),
                any(String.class), eq(IllegalStateException.class.getName()), anyLong());

        assertThatThrownBy(() -> controller.chat(
                new AiChatSocketRequest("request-send-failed", "Help", null,
                        "conversation-send-failed", null, List.of(), null),
                wsPrincipal, headers))
                .isSameAs(transportFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(settlementFailure, cleanupFailure, rejectionFailure,
                                persistenceFailure));

        verify(turn).admissionRejected("transport_send_failed");
        verify(chatService).recordFailure(any(ChatRequest.class), eq(user), any(String.class),
                eq(IllegalStateException.class.getName()), anyLong());
        verify(turn).complete("admission_rejected", transportFailure);
    }

    @Test
    void preservesMultiAgentSettingsWhileAddingRestCorrelation() throws Exception {
        AiChatController controller = controller(new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        AiMultiAgentOptions options = new AiMultiAgentOptions(true, 4, "creative");
        ChatRequest request = new ChatRequest("Help me", null, null, "conversation-1",
                null, List.of(), null, "model", "high", "ask", options, null, null);
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenReturn(
                new ChatResponse("agent", "done", "conversation-1", false, List.of()));

        controller.chat(principal, request).get(1, TimeUnit.SECONDS);

        ArgumentCaptor<ChatRequest> correlated = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).prepare(correlated.capture(), eq(user), anyLong());
        assertThat(correlated.getValue().requestId()).isNotBlank();
        assertThat(correlated.getValue().multiAgent()).isEqualTo(options);
    }

    @Test
    void returnsChangeConfirmationEventsFromTheRestTransport() throws Exception {
        AiChatController controller = controller(new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-1", "conversation-1");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AiExecutionEvent> events = invocation.getArgument(2);
            events.accept(AiExecutionEvent.progress("internal progress"));
            events.accept(AiExecutionEvent.detail("change_confirmation_required",
                    "A change requires explicit approval.", Map.of(
                            "confirmationRequestId", "confirmation-1",
                            "status", "REQUESTED",
                            "expiresAt", "2099-07-15T00:00:00Z",
                            "toolName", "update_business_context",
                            "argumentsSummary", "{\"id\":1}")));
            return new ChatResponse("connectcenter-assistant", "Approval is required.",
                    "conversation-1", false, List.of());
        });

        ChatResponse response = controller.chat(principal, request).get(1, TimeUnit.SECONDS).getBody();

        assertThat(response).isNotNull();
        assertThat(response.events()).singleElement().satisfies(event -> {
            assertThat(event.requestId()).isEqualTo("request-1");
            assertThat(event.conversationId()).isEqualTo("conversation-1");
            assertThat(event.subtype()).isEqualTo("change_confirmation_required");
            assertThat(event.metadata()).containsEntry("confirmationRequestId", "confirmation-1");
        });
    }

    @Test
    void returnsPlannedAndCancelledWorkflowLifecyclesAsSystemEvents() throws Exception {
        AiChatController controller = controller(
                new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-1", "conversation-1");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AiExecutionEvent> events = invocation.getArgument(2);
            events.accept(AiExecutionEvent.detail(
                    "subagent_planned", "Research is queued.", Map.of("nodeId", "worker-1")));
            events.accept(AiExecutionEvent.detail(
                    "subagent_cancelled", "Research stopped.", Map.of("nodeId", "worker-1")));
            events.accept(AiExecutionEvent.detail(
                    "workflow_cancelled", "Workflow stopped.", Map.of("nodeId", "work-1")));
            events.accept(AiExecutionEvent.detail(
                    "workflow_stalled", "Workflow stalled.", Map.of("nodeId", "work-2")));
            return new ChatResponse("connectcenter-assistant", "Stopped.",
                    "conversation-1", false, List.of());
        });

        ChatResponse response = controller.chat(principal, request)
                .get(1, TimeUnit.SECONDS).getBody();

        assertThat(response).isNotNull();
        assertThat(response.events()).extracting(
                        AiChatSocketEvent::type, AiChatSocketEvent::subtype,
                        AiChatSocketEvent::visibility)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "system", "subagent_planned", "visible"),
                        org.assertj.core.groups.Tuple.tuple(
                                "system", "subagent_cancelled", "visible"),
                        org.assertj.core.groups.Tuple.tuple(
                                "system", "workflow_cancelled", "visible"),
                        org.assertj.core.groups.Tuple.tuple(
                                "system", "workflow_stalled", "visible"));
    }

    @Test
    void streamsAndReturnsChangeApprovalDecisionsBeforeTheRestResponseCompletes() throws Exception {
        AiChatController controller = controller(
                new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-1", "conversation-1");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AiExecutionEvent> events = invocation.getArgument(2);
            events.accept(AiExecutionEvent.detail("change_approval_batch_required",
                    "Two actions require approval.", Map.of(
                            "batchId", "batch-1", "parallel", true,
                            "expiresAt", "2099-07-15T00:00:00Z",
                            "items", List.of(
                                    Map.of("confirmationRequestId", "confirmation-1",
                                            "toolName", "update_bbie",
                                            "argumentsSummary", "{\"id\":1}"),
                                    Map.of("confirmationRequestId", "confirmation-2",
                                            "toolName", "delete_bbie",
                                            "argumentsSummary", "{\"id\":2}")))));
            events.accept(AiExecutionEvent.detail("change_approval_decision_accepted",
                    "Approved 1 change and denied 1. Continuing the active request.",
                    Map.of("batchId", "batch-1")));
            return new ChatResponse("connectcenter-assistant", "Done.",
                    "conversation-1", false, List.of());
        });

        ChatResponse response = controller.chat(principal, request)
                .get(1, TimeUnit.SECONDS).getBody();

        assertThat(response).isNotNull();
        assertThat(response.events()).extracting(AiChatSocketEvent::subtype)
                .containsExactly("change_approval_batch_required",
                        "change_approval_decision_accepted");
        assertThat(response.events().getLast().metadata()).containsEntry("batchId", "batch-1");
        verify(messagingTemplate, times(2)).convertAndSendToUser(eq("tester"),
                eq("/queue/ai/chat/request-1"), any(AiChatSocketEvent.class));
    }

    @Test
    void delegatesOneBatchDecisionToTheCoordinatorWithoutPublishingAnOutOfBandAck() {
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers,
                new AiRequestRegistry(), mock(AiChangeConfirmationService.class),
                mock(AiElicitationService.class), approvals, properties, Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        AiChangeApprovalDecisionRequest command = new AiChangeApprovalDecisionRequest(
                "request-1", "conversation-1", "batch-1", List.of(
                new AiChangeApprovalDecisionRequest.ItemDecision("confirmation-1", "APPROVE"),
                new AiChangeApprovalDecisionRequest.ItemDecision("confirmation-2", "DENY")));
        controller.decideChangeApproval(command, wsPrincipal, headers);

        verify(approvals).decide(user, command);
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-1"), any());
    }

    @Test
    void publishesSanitizedElicitationDecisionsAndIsolatesObserverFailureFromAcknowledgement() {
        AiElicitationService elicitations = mock(AiElicitationService.class);
        List<ExecutionObservation> observations = new ArrayList<>();
        ExecutionObserver observer = ExecutionObserver.composite(List.of(
                ignored -> { throw new IllegalStateException("optional observer unavailable"); },
                observations::add));
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers,
                new AiRequestRegistry(), mock(AiChangeConfirmationService.class),
                elicitations, mock(AiChangeApprovalCoordinator.class),
                new ScoreAiProperties(), ScoreAiObservability.noop(), observer, Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        AiElicitationDecisionRequest accepted = new AiElicitationDecisionRequest(
                "request-accepted", "conversation-accepted", "elicitation-accepted",
                "ACCEPT", Map.of("private", "DO_NOT_OBSERVE"), 7L);
        AiElicitationDecisionRequest rejected = new AiElicitationDecisionRequest(
                "request-rejected", "conversation-rejected", "elicitation-rejected",
                "ACCEPT", Map.of("private", "DO_NOT_OBSERVE"), 8L);
        doThrow(new IllegalArgumentException("already answered"))
                .when(elicitations).decide(user, rejected.requestId(), rejected.conversationId(),
                        rejected.elicitationId(), rejected.generation(),
                        rejected.action(), rejected.content());

        controller.decideElicitation(accepted, wsPrincipal, headers);
        controller.decideElicitation(rejected, wsPrincipal, headers);

        assertThat(observations).hasSize(2);
        assertThat(observations).allSatisfy(observation -> {
            assertThat(observation.type()).isEqualTo(AiExecutionLifecycle.OBSERVATION_TYPE);
            assertThat(observation.scope().requesterId()).isEqualTo("1");
            assertThat(observation.toString()).doesNotContain("DO_NOT_OBSERVE");
        });
        assertThat(observations).extracting(observation ->
                        AiExecutionLifecycle.from(observation).orElseThrow().subtype())
                .containsExactly("elicitation_decision_accepted", "elicitation_decision_rejected");
        assertThat(observations).extracting(observation -> observation.scope().requestId())
                .containsExactly("request-accepted", "request-rejected");
        ArgumentCaptor<AiChatSocketEvent> acknowledgements =
                ArgumentCaptor.forClass(AiChatSocketEvent.class);
        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq("tester"), any(String.class), acknowledgements.capture());
        assertThat(acknowledgements.getAllValues()).extracting(AiChatSocketEvent::subtype)
                .containsExactly("elicitation_decision_accepted", "elicitation_decision_rejected");
    }

    @Test
    void republishesAnAcknowledgementForAnExactCommittedBatchRetry() {
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers,
                new AiRequestRegistry(), mock(AiChangeConfirmationService.class),
                mock(AiElicitationService.class), approvals,
                new ScoreAiProperties(), Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        AiChangeApprovalDecisionRequest command = new AiChangeApprovalDecisionRequest(
                "request-1", "conversation-1", "batch-1", List.of(
                new AiChangeApprovalDecisionRequest.ItemDecision(
                        "confirmation-1", "APPROVE")));
        when(approvals.decide(user, command)).thenReturn(
                new AiChangeApprovalCoordinator.DecisionAcknowledgement("batch-1", 1, 0));

        controller.decideChangeApproval(command, wsPrincipal, headers);

        ArgumentCaptor<AiChatSocketEvent> event = ArgumentCaptor.forClass(AiChatSocketEvent.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-1"), event.capture());
        assertThat(event.getValue().subtype())
                .isEqualTo("change_approval_decision_accepted");
        assertThat(event.getValue().metadata())
                .containsEntry("batchId", "batch-1")
                .containsEntry("replayed", true);
    }

    @Test
    void legacySingleDecisionCannotMutateAChildConfirmationOwnedByAnActiveRootRequest() {
        AiRequestRegistry registry = new AiRequestRegistry();
        registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(30));
        AiChangeConfirmationService confirmations =
                mock(AiChangeConfirmationService.class);
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        when(confirmations.sourceRequestId(
                user, "child-conversation-1", "confirmation-1"))
                .thenReturn("request-1");
        when(approvals.whileConfirmationUnreserved(eq("confirmation-1"), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>)
                        invocation.getArgument(1)).get());
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers, registry,
                confirmations, mock(AiElicitationService.class),
                approvals, new ScoreAiProperties(), Runnable::run);
        when(sessionService.asScoreUser(principal)).thenReturn(user);

        assertThatThrownBy(() -> controller.decideChangeConfirmation(
                principal, "child-conversation-1", "confirmation-1",
                new AiChangeConfirmationDecisionRequest("APPROVE", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active AI request");

        verify(approvals).whileConfirmationUnreserved(eq("confirmation-1"), any());
        verify(confirmations, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    void staleRestCancellationCannotCancelCurrentApprovalOrElicitationWaits() {
        AiRequestRegistry registry = mock(AiRequestRegistry.class);
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        AiElicitationService elicitations = mock(AiElicitationService.class);
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers, registry,
                mock(AiChangeConfirmationService.class), elicitations, approvals,
                new ScoreAiProperties(), Runnable::run);
        AiCancelRequest command = new AiCancelRequest(
                "request-1", "cancel-1", "conversation-stale", 6L);
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(registry.cancel("request-1", "cancel-1", "conversation-stale", 6L, user))
                .thenReturn(staleCancellation());

        ResponseEntity<AiCancellationResponse> response =
                controller.cancel(principal, "request-1", command);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().disposition()).isEqualTo("STALE_GENERATION");
        verify(elicitations, never()).cancelRequest(any());
        verify(approvals, never()).cancelRequest(any());
    }

    @Test
    void staleWebSocketCancellationCannotCancelCurrentApprovalOrElicitationWaits() {
        AiRequestRegistry registry = mock(AiRequestRegistry.class);
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        AiElicitationService elicitations = mock(AiElicitationService.class);
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers, registry,
                mock(AiChangeConfirmationService.class), elicitations, approvals,
                new ScoreAiProperties(), Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        AiCancelRequest command = new AiCancelRequest(
                "request-1", "cancel-1", "conversation-stale", 6L);
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        when(registry.cancel("request-1", "cancel-1", "conversation-stale", 6L, user))
                .thenReturn(staleCancellation());

        controller.cancel(command, wsPrincipal, headers);

        verify(elicitations, never()).cancelRequest(any());
        verify(approvals, never()).cancelRequest(any());
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-1"), sent.capture());
        assertThat(sent.getValue()).isInstanceOfSatisfying(AiChatSocketEvent.class,
                event -> assertThat(event.metadata())
                        .containsEntry("disposition", "STALE_GENERATION"));
    }

    @Test
    void exposesMultiAgentLifecycleAsVisibleSystemEventsFromRest() throws Exception {
        AiChatController controller = controller(new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-1", "conversation-1");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AiExecutionEvent> events = invocation.getArgument(2);
            events.accept(AiExecutionEvent.detail("subagent_started", "Specialist started.", Map.of(
                    "agentId", "fanout-1-agent-01",
                    "nodeId", "fanout-1-agent-01",
                    "agentName", "requirements-analyst",
                    "agentRole", "requirements analysis")));
            return new ChatResponse("connectcenter-assistant", "Done.",
                    "conversation-1", false, List.of());
        });

        ChatResponse response = controller.chat(principal, request).get(1, TimeUnit.SECONDS).getBody();

        assertThat(response).isNotNull();
        assertThat(response.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("system");
            assertThat(event.subtype()).isEqualTo("subagent_started");
            assertThat(event.metadata())
                    .containsEntry("agentId", "fanout-1-agent-01")
                    .containsEntry("agentName", "requirements-analyst");
        });
        ArgumentCaptor<Object> streamed = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-1"), streamed.capture());
        assertThat(streamed.getValue()).isInstanceOfSatisfying(
                org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent.class,
                event -> assertThat(event.subtype()).isEqualTo("subagent_started"));
    }

    @Test
    void streamsAndReplaysRestToolFailureGuideAndRetryInOrder() throws Exception {
        AiChatController controller = controller(new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-1", "conversation-1");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AiExecutionEvent> events = invocation.getArgument(2);
            events.accept(AiExecutionEvent.tool("failed", "create_item failed.",
                    "call-1", "create_item", 1L,
                    Map.of("toolDetail", "create_item\nError: value must be an integer.")));
            events.accept(AiExecutionEvent.detail("guide",
                    "I corrected the tool arguments and am retrying it.",
                    Map.of("tool_retry", true)));
            events.accept(AiExecutionEvent.tool("started", "Calling create_item.",
                    "call-2", "create_item", 2L));
            events.accept(AiExecutionEvent.tool("completed", "create_item completed.",
                    "call-2", "create_item", 2L));
            return new ChatResponse("connectcenter-assistant", "Done.",
                    "conversation-1", false, List.of());
        });

        ChatResponse response = controller.chat(principal, request)
                .get(1, TimeUnit.SECONDS).getBody();

        assertThat(response).isNotNull();
        assertThat(response.events()).extracting(
                event -> event.type() + "/" + event.subtype())
                .containsExactly("tool_call/failed", "system/guide",
                        "tool_call/started", "tool_call/completed");
        ArgumentCaptor<Object> streamed = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(4)).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-1"), streamed.capture());
        assertThat(streamed.getAllValues()).extracting(value -> {
            AiChatSocketEvent event = (AiChatSocketEvent) value;
            return event.type() + "/" + event.subtype();
        }).containsExactly("tool_call/failed", "system/guide",
                "tool_call/started", "tool_call/completed");
    }

    @Test
    void ordersConcurrentRestEventsByTheirAssignedSequence() {
        var second = org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent.system(
                "request-1", "conversation-1", 2L, "subagent_completed", "second", Map.of());
        var first = org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent.system(
                "request-1", "conversation-1", 1L, "subagent_started", "first", Map.of());

        assertThat(AiChatController.orderedResponseEvents(List.of(second, first)))
                .extracting(event -> event.sequence())
                .containsExactly(1L, 2L);
    }

    @Test
    void routesWorkflowResultsAsMainChatSystemEvents() {
        ChatRequest request = request("request-1", "conversation-1");
        AiExecutionEvent result = AiExecutionEvent.detail(
                "workflow_result", "Reconciled answer.", Map.of("depth", 1));

        AiChatSocketEvent event = AiChatController.socketEvent(request, 7L, result);

        assertThat(event.type()).isEqualTo("system");
        assertThat(event.subtype()).isEqualTo("workflow_result");
        assertThat(event.content()).isEqualTo("Reconciled answer.");
        assertThat(event.sequence()).isEqualTo(7L);
    }

    @Test
    void routesContentlessAgentLifecycleBySubtypeAndMetadata() {
        ChatRequest request = request("request-1", "conversation-1");
        AiExecutionEvent started = AiExecutionEvent.detail(
                "subagent_started", "", Map.of(
                        "nodeId", "agent-1", "taskLabel", "Verify release"));

        AiChatSocketEvent event = AiChatController.socketEvent(request, 8L, started);

        assertThat(event.type()).isEqualTo("system");
        assertThat(event.subtype()).isEqualTo("subagent_started");
        assertThat(event.content()).isEmpty();
        assertThat(event.metadata())
                .containsEntry("nodeId", "agent-1")
                .containsEntry("taskLabel", "Verify release");
    }

    @Test
    void recordsTimedOutRestRequestsInTheTrajectory() throws Exception {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setRequestInactivityTimeout(Duration.ofMillis(25));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiChatController controller = controller(new AiRequestRegistry(), properties, executor);
            ChatRequest request = request("request-timeout", "conversation-timeout");
            when(sessionService.asScoreUser(principal)).thenReturn(user);
            when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(chatService.rootAgentId()).thenReturn("configured-root-agent");
            when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<AiExecutionEvent> events = invocation.getArgument(2);
                events.accept(changeNotice());
                try {
                    Thread.sleep(60_000);
                    return new ChatResponse("agent", "late", "conversation-timeout", false, List.of());
                } catch (InterruptedException expected) {
                    throw new CancellationException("deadline interrupted the worker");
                }
            });

            ResponseEntity<ChatResponse> response = controller.chat(principal, request)
                    .get(1, TimeUnit.SECONDS);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().agent()).isEqualTo("configured-root-agent");
            assertThat(response.getBody().events()).hasSize(2);
            assertThat(response.getBody().events().getFirst().metadata())
                    .containsEntry("confirmationRequestId", "confirmation-1");
            assertThat(response.getBody().events().getLast()).satisfies(event -> {
                assertThat(event.subtype()).isEqualTo("request_error");
                assertThat(event.sequence()).isEqualTo(2L);
                assertThat(event.content()).contains(
                        "No further assistant activity was received",
                        "retry only the unfinished part");
                assertThat(event.metadata())
                        .containsEntry("status", "TIMED_OUT")
                        .containsEntry("terminal", true);
            });
            verify(chatService, timeout(1_000)).recordFailure(any(ChatRequest.class), eq(user),
                    eq("No further assistant activity was received before the inactivity timeout, "
                            + "so the request was stopped. Any changes already reported as "
                            + "completed remain applied. Review the conversation and affected "
                            + "records, then retry only the unfinished part."),
                    eq(CancellationException.class.getName()), anyLong());
        }
    }

    @Test
    void reservesAnExistingConversationBeforePreparationSideEffects() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiChatController controller = controller(registry, new ScoreAiProperties(), Runnable::run);
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong())).thenAnswer(invocation -> {
            preparing.countDown();
            if (!releasePreparation.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test preparation was not released");
            }
            return invocation.getArgument(0);
        });
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenReturn(
                new ChatResponse("agent", "done", "conversation-1", false, List.of()));

        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ResponseEntity<ChatResponse>> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return controller.chat(principal, request("request-1", "conversation-1")).get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }, caller);
            assertThat(preparing.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> controller.chat(
                    principal, request("request-2", "conversation-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active request");

            releasePreparation.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(chatService, times(1)).prepare(
                    any(ChatRequest.class), eq(user), anyLong());
        } finally {
            releasePreparation.countDown();
        }
    }

    @Test
    void reportsWebSocketAdmissionFailuresAsTerminalErrorEventsOnTheRequesterQueue() {
        AiChatController controller = controller(new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong())).thenThrow(
                new IllegalArgumentException("A prompt must not exceed the configured length."));
        AiChatSocketRequest socketRequest = new AiChatSocketRequest("request-1", "Help me", null,
                "conversation-1", null, List.of(), null);

        assertThatThrownBy(() -> controller.chat(socketRequest, wsPrincipal, headers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A prompt must not exceed the configured length.");

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-1"), sent.capture());
        assertThat(sent.getValue()).isInstanceOfSatisfying(AiChatSocketEvent.class, event -> {
            assertThat(event.type()).isEqualTo("system");
            assertThat(event.subtype()).isEqualTo("error");
            assertThat(event.requestId()).isEqualTo("request-1");
            assertThat(event.conversationId()).isEqualTo("conversation-1");
            assertThat(event.content()).isEqualTo("A prompt must not exceed the configured length.");
        });
    }

    @Test
    void closesTheExactGenerationEvenWhenRejectedRequestSettlementFails() {
        AiRequestRegistry registry = spy(new AiRequestRegistry());
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        AiChatController controller = new AiChatController(
                chatService, sessionService, messagingTemplate, webSocketUsers, registry,
                mock(AiChangeConfirmationService.class), mock(AiElicitationService.class),
                mock(AiChangeApprovalCoordinator.class), new ScoreAiProperties(), observability,
                Runnable::run);
        IllegalArgumentException admission =
                new IllegalArgumentException("invalid prepared request");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenThrow(admission);
        doThrow(new AiSharedStateUnavailableException("settlement unavailable"))
                .when(registry).finish(any(AiRequestRegistry.Entry.class), eq(admission));

        assertThatThrownBy(() -> controller.chat(
                principal, request("request-settlement", "conversation-1")))
                .isSameAs(admission)
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
        ArgumentCaptor<Long> generation = ArgumentCaptor.forClass(Long.class);
        verify(observability).recordAdmissionRejection(
                any(ChatRequest.class), eq(user), eq(admission), any(String.class),
                nullable(String.class), nullable(String.class), generation.capture());
        assertThat(generation.getValue()).isPositive();
    }

    @Test
    void stillEndsTheTurnOnTheSocketWhenTheSharedRequestStateCannotBeSettled() {
        AiRequestRegistry registry = spy(new AiRequestRegistry());
        doThrow(new AiSharedStateUnavailableException("The AI request state stayed locked."))
                .when(registry).finish(any(AiRequestRegistry.Entry.class), nullable(Throwable.class));
        AiChatController controller = controller(registry, new ScoreAiProperties(), Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenReturn(
                new ChatResponse("assistant", "Here is the comparison.", "conversation-stuck",
                        false, List.of()));

        controller.chat(new AiChatSocketRequest("request-stuck", "Help", null,
                "conversation-stuck", null, List.of(), null), wsPrincipal, headers);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-stuck"), sent.capture());
        assertThat(sent.getAllValues().get(1)).isInstanceOfSatisfying(AiChatSocketEvent.class, event -> {
            assertThat(event.type()).isEqualTo("assistant_final");
            assertThat(event.requestId()).isEqualTo("request-stuck");
            assertThat(event.content()).isEqualTo("Here is the comparison.");
        });
    }

    @Test
    void explainsUnverifiedChangesAndRecoveryOnTheRequesterQueue() {
        AiChatController controller = controller(
                new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        Principal wsPrincipal = mock(Principal.class);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        when(webSocketUsers.resolve(eq(wsPrincipal), any())).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong()))
                .thenThrow(new AiChangeReadBackException(3));

        controller.chat(new AiChatSocketRequest("request-readback", "Help", null,
                "conversation-readback", null, List.of(), null), wsPrincipal, headers);

        ArgumentCaptor<AiChatSocketEvent> sent =
                ArgumentCaptor.forClass(AiChatSocketEvent.class);
        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq("tester"), eq("/queue/ai/chat/request-readback"), sent.capture());
        AiChatSocketEvent terminal = sent.getAllValues().get(1);
        assertThat(terminal.type()).isEqualTo("system");
        assertThat(terminal.subtype()).isEqualTo("request_error");
        assertThat(terminal.content())
                .contains("completed 3 change operations")
                .contains("could not verify the final state")
                .contains("Refresh or inspect the affected records")
                .doesNotContain("server log");
        assertThat(terminal.metadata())
                .containsEntry("status", "FAILED")
                .containsEntry("terminal", true);
        verify(chatService).recordFailure(any(ChatRequest.class), eq(user),
                eq(terminal.content()), eq(AiChangeReadBackException.class.getName()), anyLong());
    }

    @Test
    void explainsUnverifiedChangesInARestErrorWithoutPriorEvents() throws Exception {
        AiChatController controller = controller(
                new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-rest-readback", "conversation-rest-readback");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.rootAgentId()).thenReturn("configured-root-agent");
        when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong()))
                .thenThrow(new AiChangeReadBackException(2));

        ResponseEntity<ChatResponse> response = controller.chat(principal, request).get();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("system");
            assertThat(event.subtype()).isEqualTo("request_error");
            assertThat(event.content())
                    .contains("completed 2 change operations")
                    .contains("could not verify the final state")
                    .contains("complete any remaining work")
                    .doesNotContain("server log");
            assertThat(event.metadata())
                    .containsEntry("status", "FAILED")
                    .containsEntry("terminal", true);
        });
        verify(chatService).recordFailure(any(ChatRequest.class), eq(user),
                eq(response.getBody().events().getFirst().content()),
                eq(AiChangeReadBackException.class.getName()), anyLong());
    }

    @Test
    void surfacesTheProviderErrorMessageWhenRetriesAreExhausted() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiChatController controller = controller(
                    new AiRequestRegistry(), new ScoreAiProperties(), executor);
            ChatRequest request = request("request-provider", "conversation-provider");
            when(sessionService.asScoreUser(principal)).thenReturn(user);
            when(chatService.prepare(any(ChatRequest.class), eq(user), anyLong()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            String providerMessage = "This request would exceed your rate limit tier of"
                    + " 50,000,000 input tokens per minute.";
            when(chatService.chat(any(ChatRequest.class), eq(user), any(), anyLong())).thenThrow(
                    new org.oagi.score.gateway.http.api.ai_management.provider.AiProviderException(
                            new org.oagi.score.gateway.http.api.ai_management.provider.AiProviderFailure(
                                    "org.springframework.ai.retry.TransientAiException", 429,
                                    providerMessage, true, null),
                            10, new IllegalStateException("429: rate_limit_error")));

            ResponseEntity<ChatResponse> response = controller.chat(principal, request)
                    .get(5, TimeUnit.SECONDS);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().events()).singleElement().satisfies(event -> {
                assertThat(event.subtype()).isEqualTo("request_error");
                assertThat(event.content())
                        .isEqualTo(providerMessage + " (failed after 10 attempts)");
            });

            // The provider's own message reaches both the REST terminal event and
            // persisted error step while diagnostics keep the original root cause.
            verify(chatService, timeout(1_000)).recordFailure(any(ChatRequest.class), eq(user),
                    eq(providerMessage + " (failed after 10 attempts)"),
                    eq(IllegalStateException.class.getName()), anyLong());
        }
    }

    private AiChatController controller(AiRequestRegistry registry, ScoreAiProperties properties,
                                        java.util.concurrent.Executor executor) {
        return new AiChatController(chatService, sessionService, messagingTemplate,
                webSocketUsers, registry,
                mock(AiChangeConfirmationService.class), properties, executor);
    }

    private ChatRequest request(String requestId, String conversationId) {
        return new ChatRequest("Help me", requestId, null, conversationId,
                null, List.of(), null);
    }

    private AiExecutionEvent changeNotice() {
        return AiExecutionEvent.detail("change_confirmation_required",
                "A change requires explicit approval.", Map.of(
                        "confirmationRequestId", "confirmation-1",
                        "status", "REQUESTED",
                        "expiresAt", "2099-07-15T00:00:00Z",
                        "toolName", "update_business_context",
                        "argumentsSummary", "{\"id\":1}"));
    }

    private AiCancellationResponse staleCancellation() {
        return new AiCancellationResponse(
                "request-1", "conversation-1", 7L,
                "cancel-1", null, "STALE_GENERATION", "RUNNING",
                false, false, 3L, null, null, null, null);
    }
}
