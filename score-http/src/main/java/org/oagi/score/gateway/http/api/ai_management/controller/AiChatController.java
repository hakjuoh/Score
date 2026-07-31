package org.oagi.score.gateway.http.api.ai_management.controller;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancelRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatModelInfo;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationModelResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationModelUpdateRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationRestoreRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiPublicExecutionRequestStatus;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeConfirmationDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeConfirmationDecisionResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiElicitationDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.oagi.score.gateway.http.configuration.websocket.WebSocketSessionUserResolver;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
@RestController
@RequestMapping("/ai/chat")
public class AiChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatController.class);
    private final ChatService chatService;
    private final SessionService sessionService;
    private final WebSocketSessionUserResolver webSocketUsers;
    private final AiRequestRegistry requests;
    private final Executor executor;
    private final AiElicitationService elicitations;
    private final AiChangeApprovalCoordinator changeApprovals;
    private final AiChatInteractionHandler interactions;
    private final AiChatAdmissionService admissions;
    private final AiChatRequestFinalizer finalizer;
    private final AiChangeConfirmationEndpoint confirmationEndpoint;

    @Autowired
    public AiChatController(ChatService chatService, SessionService sessionService,
                            SimpMessagingTemplate messagingTemplate,
                            WebSocketSessionUserResolver webSocketUsers,
                            AiRequestRegistry requests,
                            AiChangeConfirmationService changeConfirmations,
                            AiElicitationService elicitations,
                            AiChangeApprovalCoordinator changeApprovals,
                            ScoreAiProperties aiProperties,
                            ScoreAiObservability observability,
                            ObjectProvider<ExecutionObserver> executionObservers,
                            @Qualifier("scoreAiChatExecutor") Executor executor) {
        this(chatService, sessionService, messagingTemplate, webSocketUsers, requests,
                changeConfirmations, elicitations, changeApprovals, aiProperties,
                observability, executionObservers.getIfAvailable(ExecutionObserver::noop), executor);
    }

    AiChatController(ChatService chatService, SessionService sessionService,
                     SimpMessagingTemplate messagingTemplate,
                     WebSocketSessionUserResolver webSocketUsers,
                     AiRequestRegistry requests,
                     AiChangeConfirmationService changeConfirmations,
                     AiElicitationService elicitations,
                     AiChangeApprovalCoordinator changeApprovals,
                     ScoreAiProperties aiProperties,
                     ScoreAiObservability observability,
                     Executor executor) {
        this(chatService, sessionService, messagingTemplate, webSocketUsers, requests,
                changeConfirmations, elicitations, changeApprovals, aiProperties,
                observability, ExecutionObserver.noop(), executor);
    }

    AiChatController(ChatService chatService, SessionService sessionService,
                     SimpMessagingTemplate messagingTemplate,
                     WebSocketSessionUserResolver webSocketUsers,
                     AiRequestRegistry requests,
                     AiChangeConfirmationService changeConfirmations,
                     AiElicitationService elicitations,
                     AiChangeApprovalCoordinator changeApprovals,
                     ScoreAiProperties aiProperties,
                     ScoreAiObservability observability,
                     ExecutionObserver observer,
                     Executor executor) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.webSocketUsers = webSocketUsers;
        this.requests = requests;
        this.elicitations = elicitations;
        this.changeApprovals = changeApprovals;
        this.interactions = new AiChatInteractionHandler(chatService, requests, elicitations,
                changeApprovals, observer, messagingTemplate);
        this.admissions = new AiChatAdmissionService(chatService, requests, observability,
                aiProperties.getRequestInactivityTimeout());
        this.finalizer = new AiChatRequestFinalizer(chatService, requests, changeApprovals);
        this.confirmationEndpoint = new AiChangeConfirmationEndpoint(
                requests, changeConfirmations, changeApprovals);
        this.executor = executor;
    }

    AiChatController(ChatService chatService, SessionService sessionService,
                     SimpMessagingTemplate messagingTemplate,
                     WebSocketSessionUserResolver webSocketUsers,
                     AiRequestRegistry requests,
                     AiChangeConfirmationService changeConfirmations,
                     ScoreAiProperties aiProperties,
                     Executor executor) {
        this(chatService, sessionService, messagingTemplate, webSocketUsers, requests,
                changeConfirmations, new AiElicitationService(aiProperties, requests), null,
                aiProperties, ScoreAiObservability.noop(), executor);
    }

    AiChatController(ChatService chatService, SessionService sessionService,
                     SimpMessagingTemplate messagingTemplate,
                     WebSocketSessionUserResolver webSocketUsers,
                     AiRequestRegistry requests,
                     AiChangeConfirmationService changeConfirmations,
                     AiElicitationService elicitations,
                     AiChangeApprovalCoordinator changeApprovals,
                     ScoreAiProperties aiProperties,
                     Executor executor) {
        this(chatService, sessionService, messagingTemplate, webSocketUsers, requests,
                changeConfirmations, elicitations, changeApprovals, aiProperties,
                ScoreAiObservability.noop(), executor);
    }

    @PostMapping
    public CompletableFuture<ResponseEntity<ChatResponse>> chat(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "tracestate", required = false) String tracestate) {
        ScoreUser requester = sessionService.asScoreUser(principal);
        AiChatAdmissionService.Admission admission = admissions.prepare(
                request, requester, traceparent, tracestate);
        ChatRequest prepared = admission.request();
        AiRequestRegistry.Entry entry = admission.entry();
        ScoreAiObservability.Turn observation = admission.observation();
        List<AiChatSocketEvent> responseEvents = new CopyOnWriteArrayList<>();
        AtomicLong sequence = new AtomicLong();
        CompletableFuture<ChatResponse> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                if (!requests.start(entry)) {
                    throw new CancellationException("The request was cancelled before execution started.");
                }
                observation.executionStarted();
                ChatResponse response = chatService.chat(prepared, requester, event -> {
                    requests.progress(prepared.requestId());
                    if (AiChatTransport.isRestResponseEvent(event)) {
                        AiChatSocketEvent socketEvent = socketEvent(
                                prepared, sequence.incrementAndGet(), event);
                        responseEvents.add(socketEvent);
                        // Interaction events must arrive while the HTTP request is still
                        // running so failed tool rows and retry narration remain ordered.
                        if (AiChatTransport.isRestLiveEvent(event)) {
                            try {
                                send(requester, queue(prepared.requestId()), socketEvent);
                            } catch (RuntimeException exception) {
                                LOGGER.warn("Could not stream an HTTP chat interaction event to the user", exception);
                            }
                        }
                    }
                }, entry.generation());
                return response.withEvents(orderedResponseEvents(responseEvents));
            }, executor);
        } catch (RuntimeException failure) {
            finalizer.finishBeforeExecution(entry, prepared, requester, observation,
                    "executor_rejected", failure);
            throw failure;
        }
        return future.handle((response, throwable) -> {
            String status;
            try {
                status = requests.finish(entry, throwable);
            } catch (RuntimeException finishFailure) {
                observation.complete("FAILED", finishFailure);
                throw finishFailure;
            }
            try {
                finalizer.clear(prepared.requestId());
                if ("FAILED".equals(status) || "TIMED_OUT".equals(status)) {
                    chatService.recordFailure(prepared, requester,
                            AiChatTransport.terminalMessage(status, throwable),
                            AiChatTransport.failureClass(throwable),
                            entry.generation());
                }
                if ("COMPLETED".equals(status)) {
                    return ResponseEntity.ok(response);
                }
                if (!responseEvents.isEmpty()) {
                    return ResponseEntity.status(AiChatTransport.restTerminalStatus(status)).body(new ChatResponse(
                            chatService.rootAgentId(), null, prepared.conversationId(),
                            false, List.of(), orderedResponseEvents(responseEvents)));
                }
                throw AiChatTransport.propagate(throwable, status);
            } finally {
                observation.complete(status, throwable);
            }
        });
    }

    CompletableFuture<ResponseEntity<ChatResponse>> chat(
            AuthenticatedPrincipal principal, ChatRequest request) {
        return chat(principal, request, null, null);
    }

    @GetMapping("/conversations")
    public List<ChatConversationSummary> conversations(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return chatService.conversations(sessionService.asScoreUser(principal));
    }

    @GetMapping("/models")
    public List<AiChatModelInfo> models() {
        return chatService.availableModels();
    }

    @GetMapping("/conversations/{conversationId}")
    public ChatConversationDetails conversation(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                @PathVariable String conversationId) {
        return chatService.conversation(sessionService.asScoreUser(principal), conversationId);
    }

    @PatchMapping("/conversations/{conversationId}/model")
    public AiConversationModelResponse updateConversationModel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String conversationId,
            @RequestBody AiConversationModelUpdateRequest request,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "tracestate", required = false) String tracestate) {
        return requests.whileConversationIdle(conversationId, () -> chatService.updateConversationModel(
                sessionService.asScoreUser(principal), conversationId,
                request.modelName(), request.reasoningEffort(), traceparent, tracestate));
    }

    @GetMapping("/conversations/{conversationId}/trajectory")
    public Map<String, Object> trajectory(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                          @PathVariable String conversationId) {
        return chatService.trajectory(sessionService.asScoreUser(principal), conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Boolean> deleteConversation(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                   @PathVariable String conversationId) {
        return requests.whileConversationIdle(conversationId, () -> Map.of("deleted",
                chatService.deleteConversation(sessionService.asScoreUser(principal), conversationId)));
    }

    @PostMapping("/conversations/{conversationId}/change-confirmations/{confirmationRequestId}/decision")
    public ResponseEntity<AiChangeConfirmationDecisionResponse> decideChangeConfirmation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String confirmationRequestId,
            @RequestBody AiChangeConfirmationDecisionRequest request) {
        ScoreUser requester = sessionService.asScoreUser(principal);
        return confirmationEndpoint.decide(
                requester, conversationId, confirmationRequestId, request);
    }

    @PostMapping("/availability/revalidate")
    public Map<String, Boolean> revalidateAvailability() {
        return Map.of("available", chatService.aiAssistantInfo().enabled());
    }

    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<AiCancellationResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String requestId,
            @RequestBody AiCancelRequest command) {
        ScoreUser requester = sessionService.asScoreUser(principal);
        String cancellationId = StringUtils.hasText(command.cancellationRequestId())
                ? command.cancellationRequestId() : UUID.randomUUID().toString();
        AiCancellationResponse response = requests.cancel(requestId, cancellationId,
                command.conversationId(), command.expectedGeneration(), requester);
        if (response.acknowledged()) {
            elicitations.cancelRequest(requestId);
            if (changeApprovals != null) {
                changeApprovals.cancelRequest(requestId);
            }
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{requestId}/status")
    public AiPublicExecutionRequestStatus status(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String requestId,
            @RequestParam String conversationId,
            @RequestParam long expectedGeneration) {
        AiPublicExecutionRequestStatus status = requests.status(requestId, sessionService.asScoreUser(principal));
        if (!status.conversationId().equals(conversationId) || status.generation() != expectedGeneration) {
            throw new IllegalArgumentException("The request identity does not match the active generation.");
        }
        return status;
    }

    @GetMapping("/active-request")
    public ResponseEntity<AiPublicExecutionRequestStatus> activeRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return requests.active(sessionService.asScoreUser(principal))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @MessageMapping("/ai/chat")
    public void chat(AiChatSocketRequest socketRequest, Principal principal,
                     SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        ChatRequest chatRequest = socketRequest.toChatRequest();
        AiChatAdmissionService.Admission admission;
        try {
            admission = admissions.prepare(chatRequest, requester,
                    headers.getFirstNativeHeader("traceparent"),
                    headers.getFirstNativeHeader("tracestate"));
        } catch (RuntimeException failure) {
            // Without a terminal event on the reply queue the web client can only
            // report a generic acknowledgement timeout instead of the actual
            // validation or admission problem.
            sendAdmissionFailure(requester, chatRequest, failure);
            throw failure;
        }
        ChatRequest prepared = admission.request();
        String destination = queue(prepared.requestId());
        Instant deadline = admission.deadline();
        AiRequestRegistry.Entry entry = admission.entry();
        ScoreAiObservability.Turn observation = admission.observation();
        try {
            send(requester, destination, AiChatSocketEvent.accepted(
                    prepared.requestId(), prepared.conversationId(), entry.generation(), deadline));
        } catch (RuntimeException failure) {
            finalizer.finishBeforeExecution(entry, prepared, requester, observation,
                    "transport_send_failed", failure);
            throw failure;
        }

        AtomicLong sequence = new AtomicLong();
        CompletableFuture<ChatResponse> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                if (!requests.start(entry)) {
                    throw new CancellationException("The request was cancelled before execution started.");
                }
                observation.executionStarted();
                return chatService.chat(prepared, requester,
                        event -> {
                            requests.progress(prepared.requestId());
                            send(requester, destination, socketEvent(
                                    prepared, sequence.incrementAndGet(), event));
                        }, entry.generation());
            }, executor);
        } catch (RuntimeException failure) {
            finalizer.finishBeforeExecution(entry, prepared, requester, observation,
                    "executor_rejected", failure);
            send(requester, destination, AiChatSocketEvent.terminalError(prepared.requestId(),
                    prepared.conversationId(), entry.generation(), "FAILED",
                    AiChatTransport.terminalMessage("FAILED", failure)));
            throw failure;
        }
        future.whenComplete((response, throwable) -> {
            String status;
            RuntimeException settlementFailure = null;
            try {
                status = requests.finish(entry, throwable);
            } catch (RuntimeException finishFailure) {
                // The reply queue is the only channel that ends the turn. Without
                // a terminal event the web client waits on a request that the
                // backend has already finished, so the outcome is derived from
                // the execution itself when shared state cannot be settled.
                settlementFailure = finishFailure;
                status = throwable == null ? "COMPLETED" : "FAILED";
                LOGGER.error("Could not settle the shared state of AI request {}",
                        prepared.requestId(), finishFailure);
            }
            try {
                finalizer.clear(prepared.requestId());
                if ("COMPLETED".equals(status)) {
                    send(requester, destination, AiChatSocketEvent.finalResponse(prepared.requestId(), response));
                } else if ("CANCELLED".equals(status)) {
                    send(requester, destination, AiChatSocketEvent.cancelled(prepared.requestId(),
                            prepared.conversationId(), entry.generation(),
                            finalizer.cancellationRequestId(entry)));
                } else if ("UNKNOWN_RECONCILIATION_REQUIRED".equals(status)) {
                    send(requester, destination, AiChatSocketEvent.reconciliationRequired(prepared.requestId(),
                            prepared.conversationId(), entry.generation()));
                } else {
                    String message = AiChatTransport.terminalMessage(status, throwable);
                    chatService.recordFailure(prepared, requester, message,
                            AiChatTransport.failureClass(throwable), entry.generation());
                    send(requester, destination, AiChatSocketEvent.terminalError(prepared.requestId(),
                            prepared.conversationId(), entry.generation(), status, message));
                }
            } catch (RuntimeException dispatchFailure) {
                LOGGER.error("Could not publish the terminal event of AI request {}",
                        prepared.requestId(), dispatchFailure);
                send(requester, destination, AiChatSocketEvent.terminalError(prepared.requestId(),
                        prepared.conversationId(), entry.generation(), "FAILED",
                        AiChatTransport.terminalMessage("FAILED", dispatchFailure)));
            } finally {
                observation.complete(status, throwable != null ? throwable : settlementFailure);
            }
        });
    }

    @MessageMapping("/ai/chat/conversation")
    public void conversation(AiConversationRestoreRequest request, Principal principal,
                             SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        interactions.restore(requester, request);
    }

    @MessageMapping("/ai/chat/cancel")
    public void cancel(AiCancelRequest command, Principal principal, SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        interactions.cancel(requester, command);
    }

    @MessageMapping("/ai/chat/elicitation")
    public void decideElicitation(AiElicitationDecisionRequest command, Principal principal,
                                  SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        interactions.decideElicitation(requester, command);
    }

    @MessageMapping("/ai/chat/change-approval")
    public void decideChangeApproval(AiChangeApprovalDecisionRequest command,
                                       Principal principal,
                                       SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        interactions.decideChangeApproval(requester, command);
    }

    private void sendAdmissionFailure(ScoreUser requester, ChatRequest request, RuntimeException failure) {
        if (!StringUtils.hasText(request.requestId())) {
            // Without a client-chosen request identifier there is no reply queue
            // the client could be listening on.
            return;
        }
        try {
            send(requester, queue(request.requestId()), AiChatSocketEvent.error(
                    request.requestId(), request.conversationId(), AiChatTransport.safeMessage(failure)));
        } catch (RuntimeException sendFailure) {
            LOGGER.warn("Could not report an AI chat admission failure to the user", sendFailure);
        }
    }

    private void send(ScoreUser requester, String destination, AiChatSocketEvent event) {
        interactions.send(requester, destination, event);
    }

    private String queue(String requestId) {
        return AiChatInteractionHandler.queue(requestId);
    }


    static AiChatSocketEvent socketEvent(ChatRequest request, long sequence, AiExecutionEvent event) {
        return AiChatTransport.socketEvent(request, sequence, event);
    }

    static List<AiChatSocketEvent> orderedResponseEvents(List<AiChatSocketEvent> events) {
        return AiChatTransport.orderedResponseEvents(events);
    }
}
