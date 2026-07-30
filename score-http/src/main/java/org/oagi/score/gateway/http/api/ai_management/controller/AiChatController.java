package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeDecision;

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
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
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
import org.springframework.http.HttpStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@RestController
@RequestMapping("/ai/chat")
public class AiChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatController.class);
    private static final Set<String> WORKFLOW_LIFECYCLE_EVENT_TYPES = Set.of(
            "workflow_started", "workflow_completed", "workflow_failed", "workflow_cancelled",
            "workflow_refused",
            "multi_agent_started", "multi_agent_synthesizing", "multi_agent_completed",
            "multi_agent_failed", "multi_agent_cancelled",
            "subagent_planned", "subagent_started", "subagent_completed",
            "subagent_failed", "subagent_cancelled", "subagent_refused",
            "parallel_workflow_started", "parallel_workflow_synthesizing",
            "parallel_workflow_completed", "parallel_workflow_failed",
            "parallel_workflow_cancelled", "parallel_task_planned", "parallel_task_started",
            "parallel_task_completed", "parallel_task_failed", "parallel_task_cancelled");

    private final ChatService chatService;
    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionUserResolver webSocketUsers;
    private final AiRequestRegistry requests;
    private final Executor executor;
    private final AiChangeConfirmationService changeConfirmations;
    private final AiElicitationService elicitations;
    private final AiChangeApprovalCoordinator changeApprovals;
    private final Duration requestInactivityTimeout;
    private final ScoreAiObservability observability;
    private final ExecutionObserver observer;

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
        this.messagingTemplate = messagingTemplate;
        this.webSocketUsers = webSocketUsers;
        this.requests = requests;
        this.changeConfirmations = changeConfirmations;
        this.elicitations = elicitations;
        this.changeApprovals = changeApprovals;
        this.requestInactivityTimeout = aiProperties.getRequestInactivityTimeout();
        this.observability = observability;
        this.observer = observer != null ? observer : ExecutionObserver.noop();
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
        Admission admission = prepare(request, requester, traceparent, tracestate);
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
                    if (isRestResponseEvent(event)) {
                        AiChatSocketEvent socketEvent = socketEvent(
                                prepared, sequence.incrementAndGet(), event);
                        responseEvents.add(socketEvent);
                        // Interaction events must arrive while the HTTP request is still
                        // running so failed tool rows and retry narration remain ordered.
                        if (isRestLiveEvent(event)) {
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
            finishBeforeExecution(entry, prepared, requester, observation,
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
                clearChangeApprovalState(prepared.requestId());
                if ("FAILED".equals(status) || "TIMED_OUT".equals(status)) {
                    chatService.recordFailure(prepared, requester,
                            terminalMessage(status, throwable), failureClass(throwable),
                            entry.generation());
                }
                if ("COMPLETED".equals(status)) {
                    return ResponseEntity.ok(response);
                }
                if (!responseEvents.isEmpty()) {
                    return ResponseEntity.status(restTerminalStatus(status)).body(new ChatResponse(
                            chatService.rootAgentId(), null, prepared.conversationId(),
                            false, List.of(), orderedResponseEvents(responseEvents)));
                }
                throw propagate(throwable, status);
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
        String sourceRequestId = changeConfirmations.sourceRequestId(
                requester, conversationId, confirmationRequestId);
        Supplier<ResponseEntity<AiChangeConfirmationDecisionResponse>> decision =
                () -> requests.whileRequestAndConversationIdle(
                        sourceRequestId, conversationId, () -> {
                            AiChangeDecision result = changeConfirmations.decide(
                                    requester, conversationId,
                                    confirmationRequestId,
                                    request != null ? request.decision() : null,
                                    request != null ? request.revisionPrompt() : null);
                            return ResponseEntity.status(result.status()).body(
                                    changeConfirmations.bindConversation(
                                            result.response(), conversationId));
                        });
        return changeApprovals != null
                ? changeApprovals.whileConfirmationUnreserved(confirmationRequestId, decision)
                : decision.get();
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
        Admission admission;
        try {
            admission = prepare(chatRequest, requester,
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
            finishBeforeExecution(entry, prepared, requester, observation,
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
            finishBeforeExecution(entry, prepared, requester, observation,
                    "executor_rejected", failure);
            send(requester, destination, AiChatSocketEvent.terminalError(prepared.requestId(),
                    prepared.conversationId(), entry.generation(), "FAILED",
                    terminalMessage("FAILED", failure)));
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
                clearChangeApprovalState(prepared.requestId());
                if ("COMPLETED".equals(status)) {
                    send(requester, destination, AiChatSocketEvent.finalResponse(prepared.requestId(), response));
                } else if ("CANCELLED".equals(status)) {
                    send(requester, destination, AiChatSocketEvent.cancelled(prepared.requestId(),
                            prepared.conversationId(), entry.generation(), cancellationRequestId(entry)));
                } else if ("UNKNOWN_RECONCILIATION_REQUIRED".equals(status)) {
                    send(requester, destination, AiChatSocketEvent.reconciliationRequired(prepared.requestId(),
                            prepared.conversationId(), entry.generation()));
                } else {
                    String message = terminalMessage(status, throwable);
                    chatService.recordFailure(prepared, requester, message,
                            failureClass(throwable), entry.generation());
                    send(requester, destination, AiChatSocketEvent.terminalError(prepared.requestId(),
                            prepared.conversationId(), entry.generation(), status, message));
                }
            } catch (RuntimeException dispatchFailure) {
                LOGGER.error("Could not publish the terminal event of AI request {}",
                        prepared.requestId(), dispatchFailure);
                send(requester, destination, AiChatSocketEvent.terminalError(prepared.requestId(),
                        prepared.conversationId(), entry.generation(), "FAILED",
                        terminalMessage("FAILED", dispatchFailure)));
            } finally {
                observation.complete(status, throwable != null ? throwable : settlementFailure);
            }
        });
    }

    private void finishBeforeExecution(AiRequestRegistry.Entry entry, ChatRequest request,
                                       ScoreUser requester, ScoreAiObservability.Turn observation,
                                       String reason, RuntimeException failure) {
        String status;
        try {
            status = requests.finish(entry, failure);
        } catch (RuntimeException finishFailure) {
            status = "FAILED";
            if (finishFailure != failure) failure.addSuppressed(finishFailure);
            LOGGER.error("Could not settle rejected AI request {}", entry.requestId(),
                    finishFailure);
        }
        String terminalStatus = status;
        finishRejectedRequestStep(entry, failure, "clear its approval state",
                () -> clearChangeApprovalState(request.requestId()));
        finishRejectedRequestStep(entry, failure, "record its admission rejection",
                () -> observation.admissionRejected(reason));
        finishRejectedRequestStep(entry, failure, "persist its trajectory failure",
                () -> chatService.recordFailure(request, requester,
                        terminalMessage(terminalStatus, failure), failureClass(failure), entry.generation()));
        finishRejectedRequestStep(entry, failure, "complete its observation",
                () -> observation.complete("admission_rejected", failure));
    }

    private void finishRejectedRequestStep(AiRequestRegistry.Entry entry, RuntimeException failure,
                                           String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException stepFailure) {
            if (stepFailure != failure) failure.addSuppressed(stepFailure);
            LOGGER.error("Could not {} for rejected AI request {}", operation, entry.requestId(),
                    stepFailure);
        }
    }

    @MessageMapping("/ai/chat/conversation")
    public void conversation(AiConversationRestoreRequest request, Principal principal,
                             SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        String destination = queue(request.requestId());
        try {
            ChatConversationDetails details = chatService.conversation(requester, request.conversationId());
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("restoreToken", request.restoreToken());
            metadata.put("restoreSequence", request.restoreSequence());
            metadata.put("modelName", details.modelName());
            metadata.put("reasoningEffort", details.reasoningEffort());
            metadata.put("permissionMode", details.permissionMode());
            if (StringUtils.hasText(details.activeWorkflow())) {
                metadata.put("activeWorkflow", details.activeWorkflow());
            }
            if (details.contextUsage() != null) metadata.put("contextUsage", details.contextUsage());
            send(requester, destination, AiChatSocketEvent.system(request.requestId(), request.conversationId(),
                    null, "accepted", "Restoring conversation.", metadata));
            send(requester, destination, AiChatSocketEvent.historyStart(request.requestId(),
                    request.conversationId(), details.title(), metadata));
            details.messages().forEach(message -> send(requester, destination,
                    AiChatSocketEvent.historyMessage(request.requestId(), request.conversationId(), message, metadata)));
            send(requester, destination, AiChatSocketEvent.historyFinal(request.requestId(),
                    request.conversationId(), metadata));
        } catch (RuntimeException exception) {
            Map<String, Object> metadata = Map.of(
                    "restoreToken", request.restoreToken(),
                    "restoreSequence", request.restoreSequence());
            send(requester, destination, AiChatSocketEvent.system(request.requestId(), request.conversationId(),
                    null, "error", safeMessage(exception), metadata));
        }
    }

    @MessageMapping("/ai/chat/cancel")
    public void cancel(AiCancelRequest command, Principal principal, SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        String cancellationId = StringUtils.hasText(command.cancellationRequestId())
                ? command.cancellationRequestId() : UUID.randomUUID().toString();
        AiCancellationResponse response = requests.cancel(command.requestId(), cancellationId,
                command.conversationId(), command.expectedGeneration(), requester);
        if (response.acknowledged()) {
            elicitations.cancelRequest(command.requestId());
            if (changeApprovals != null) {
                changeApprovals.cancelRequest(command.requestId());
            }
        }
        AiChatSocketEvent event = "CANCELLING".equals(response.status())
                ? AiChatSocketEvent.cancellationAcknowledged(command.requestId(), response.conversationId(),
                        response.generation(), response.lifecycleEventSequence(), cancellationId)
                : AiChatSocketEvent.system(command.requestId(), response.conversationId(),
                        response.lifecycleEventSequence(), "cancellation_current_status",
                        cancellationDispositionMessage(response),
                        Map.of("generation", response.generation(), "status", response.status(),
                                "disposition", response.disposition(), "terminal", response.terminal(),
                                "acknowledged", response.acknowledged(),
                                "cancellationRequestId", cancellationId));
        send(requester, queue(command.requestId()), event);
    }

    @MessageMapping("/ai/chat/elicitation")
    public void decideElicitation(AiElicitationDecisionRequest command, Principal principal,
                                  SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        try {
            elicitations.decide(requester, command.requestId(), command.conversationId(),
                    command.elicitationId(), command.generation(), command.action(), command.content());
            observeLifecycle(requester, command.requestId(), command.conversationId(),
                    command.generation(), "elicitation_decision_accepted",
                    Map.of("elicitationId", command.elicitationId()));
            send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                    command.requestId(), command.conversationId(), null,
                    "elicitation_decision_accepted", "Your response was sent to the assistant.",
                    Map.of("elicitationId", command.elicitationId())));
        } catch (RuntimeException exception) {
            observeLifecycle(requester, command.requestId(), command.conversationId(),
                    command.generation(), "elicitation_decision_rejected",
                    Map.of("elicitationId", command.elicitationId()));
            send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                    command.requestId(), command.conversationId(), null,
                    "elicitation_decision_rejected",
                    "The assistant could not accept that response. Please try again.",
                    Map.of("elicitationId", command.elicitationId())));
        }
    }

    @MessageMapping("/ai/chat/change-approval")
    public void decideChangeApproval(AiChangeApprovalDecisionRequest command,
                                       Principal principal,
                                       SimpMessageHeaderAccessor headers) {
        ScoreUser requester = webSocketUsers.resolve(principal, headers.getSessionAttributes());
        if (changeApprovals == null) {
            throw new IllegalStateException("Change approval coordination is not available.");
        }
        try {
            AiChangeApprovalCoordinator.DecisionAcknowledgement replay =
                    changeApprovals.decide(requester, command);
            if (replay != null) {
                long approved = replay.approved();
                long denied = replay.denied();
                send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                        command.requestId(), command.conversationId(), null,
                        "change_approval_decision_accepted",
                        "Approved " + approved + " change" + (approved == 1 ? "" : "s")
                                + " and denied " + denied
                                + ". Continuing the active request.",
                        Map.of("batchId", replay.batchId(), "replayed", true)));
            }
        } catch (RuntimeException exception) {
            if (command == null || !StringUtils.hasText(command.requestId())
                    || !StringUtils.hasText(command.conversationId())
                    || !StringUtils.hasText(command.batchId())) {
                throw exception;
            }
            send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                    command.requestId(), command.conversationId(), null,
                    "change_approval_decision_rejected",
                    "The assistant could not accept that approval decision. Please try again.",
                    Map.of("batchId", command.batchId())));
        }
    }

    private Admission prepare(ChatRequest request, ScoreUser requester,
                              String traceparent, String tracestate) {
        if (request == null) {
            throw new IllegalArgumentException("Chat request must not be null.");
        }
        String requestId = StringUtils.hasText(request.requestId())
                ? request.requestId() : UUID.randomUUID().toString();
        ChatRequest correlated = new ChatRequest(request.prompt(), requestId, request.agent(),
                request.conversationId(), request.pageContext(), request.attachments(),
                request.changeConfirmation(), request.modelName(), request.reasoningEffort(),
                request.permissionMode(), request.multiAgent(), request.activeWorkflow(),
                request.routeManifest());
        Instant deadline = Instant.now().plus(requestInactivityTimeout);
        AiRequestRegistry.Entry entry;
        try {
            entry = requests.register(requestId, request.conversationId(), requester, deadline);
        } catch (RuntimeException failure) {
            observability.recordAdmissionRejection(correlated, requester, failure,
                    admissionReason(failure), traceparent, tracestate);
            throw failure;
        }
        try {
            ChatRequest prepared = chatService.prepare(correlated, requester, entry.generation());
            requests.bindConversation(entry, prepared.conversationId());
            ScoreAiObservability.Turn observation = observability.startTurn(
                    prepared, requester, entry.generation(), traceparent, tracestate);
            return new Admission(prepared, entry, deadline, observation);
        } catch (RuntimeException | Error failure) {
            try {
                requests.finish(entry, failure);
            } catch (RuntimeException | Error settlementFailure) {
                if (settlementFailure != failure) failure.addSuppressed(settlementFailure);
                LOGGER.error("Could not settle rejected AI request {}", entry.requestId(),
                        settlementFailure);
            }
            observability.recordAdmissionRejection(correlated, requester, failure,
                    admissionReason(failure), traceparent, tracestate, entry.generation());
            throw failure;
        }
    }

    private void clearChangeApprovalState(String requestId) {
        if (changeApprovals != null) {
            changeApprovals.cancelRequest(requestId);
        }
    }

    /** An unreadable cancellation correlation must not suppress the terminal event. */
    private String cancellationRequestId(AiRequestRegistry.Entry entry) {
        try {
            return requests.cancellationRequestId(entry);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not read the cancellation correlation of AI request {}",
                    entry.requestId(), failure);
            return null;
        }
    }

    private String admissionReason(Throwable failure) {
        if (failure instanceof java.util.concurrent.RejectedExecutionException) {
            return "executor_rejected";
        }
        String message = Objects.toString(failure != null ? failure.getMessage() : null, "")
                .toLowerCase(java.util.Locale.ROOT);
        if (message.contains("registry is at capacity")) return "registry_capacity";
        if (message.contains("too many ai requests")) return "user_limit";
        if (message.contains("already has an active request")) return "conversation_busy";
        if (message.contains("requestid already exists")) return "duplicate_request";
        if (failure instanceof IllegalArgumentException) return "validation";
        return "preparation_failed";
    }

    private record Admission(ChatRequest request, AiRequestRegistry.Entry entry, Instant deadline,
                             ScoreAiObservability.Turn observation) {}

    private void sendAdmissionFailure(ScoreUser requester, ChatRequest request, RuntimeException failure) {
        if (!StringUtils.hasText(request.requestId())) {
            // Without a client-chosen request identifier there is no reply queue
            // the client could be listening on.
            return;
        }
        try {
            send(requester, queue(request.requestId()), AiChatSocketEvent.error(
                    request.requestId(), request.conversationId(), safeMessage(failure)));
        } catch (RuntimeException sendFailure) {
            LOGGER.warn("Could not report an AI chat admission failure to the user", sendFailure);
        }
    }

    private void send(ScoreUser requester, String destination, AiChatSocketEvent event) {
        messagingTemplate.convertAndSendToUser(requester.username(), destination, event);
    }

    private String queue(String requestId) {
        return "/queue/ai/chat/" + requestId;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "The assistant request failed. Details were recorded in the server log.";
        }
        // The provider retry loop already classified the failure and bounded the
        // provider's own message for display; it wraps the raw provider exception,
        // so it must be found before the root-cause walk skips past it.
        for (Throwable candidate = throwable; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof org.oagi.score.gateway.http.api.ai_management.provider.AiProviderException provider) {
                LOGGER.warn("AI chat request failed at the model provider", provider);
                return provider.getMessage();
            }
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        LOGGER.warn("AI chat request failed", current);
        if (current instanceof IllegalArgumentException && StringUtils.hasText(current.getMessage())) {
            String message = current.getMessage();
            if (message.length() <= 500 && message.matches("(?i)^(attachment|attachments|unsupported ai attachment|a prompt|a maximum|the requested assistant model|the requested reasoning effort|chat request|multiagent).*$")) {
                return message;
            }
        }
        if (current instanceof java.util.concurrent.TimeoutException) {
            return "The assistant operation timed out before it reported further progress.";
        }
        String failureClass = current.getClass().getName();
        if (failureClass.startsWith("io.modelcontextprotocol.")) {
            return failureClass.contains("Authorization")
                    ? "The assistant could not authorize with the connectCenter tool service."
                    + " Data changes that already completed remain applied. Please retry."
                    : "The assistant's connection to the connectCenter tool service failed."
                    + " Data changes that already completed remain applied. Please retry.";
        }
        if (failureClass.startsWith("org.springframework.ai.retry.")
                || failureClass.startsWith("org.springframework.web.reactive.function.client.")
                || failureClass.startsWith("org.springframework.web.client.")) {
            return "The assistant's model provider could not complete the request. Please retry.";
        }
        return "The assistant request failed. Details were recorded in the server log.";
    }

    private String failureClass(Throwable throwable) {
        if (throwable == null) return null;
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private String terminalMessage(String status, Throwable throwable) {
        if ("TIMED_OUT".equals(status)) {
            if (throwable != null) {
                LOGGER.warn("AI chat request stopped after its inactivity lease expired", throwable);
            }
            return "The assistant request stopped after no observable activity.";
        }
        return safeMessage(throwable);
    }

    private HttpStatus restTerminalStatus(String status) {
        return switch (status) {
            case "TIMED_OUT" -> HttpStatus.REQUEST_TIMEOUT;
            case "FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
    }

    private RuntimeException propagate(Throwable throwable, String status) {
        if (throwable instanceof java.util.concurrent.CompletionException completionException) {
            return completionException;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable != null) {
            return new java.util.concurrent.CompletionException(throwable);
        }
        return new IllegalStateException("The AI request ended with status " + status + ".");
    }

    private String cancellationDispositionMessage(AiCancellationResponse response) {
        return switch (response.disposition()) {
            case "STALE_GENERATION" -> "Cancellation rejected because the request identity is stale.";
            case "ALREADY_TERMINAL" -> "The request was already terminal.";
            case "CANCELLED" -> "Request cancelled before execution started.";
            default -> "Cancellation status updated.";
        };
    }

    private void observeLifecycle(ScoreUser requester, String requestId, String conversationId,
                                  Long generation, String subtype,
                                  Map<String, Object> metadata) {
        if (!StringUtils.hasText(requestId) || !StringUtils.hasText(conversationId)) return;
        if (generation == null || generation <= 0L) return;
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && StringUtils.hasText(requester.username())
                ? requester.username() : "unknown";
        try {
            ExecutionScope scope = new ExecutionScope(requestId, conversationId, requesterId,
                    generation,
                    ExecutionScope.Purpose.USER_RESPONSE, List.of());
            observer.observe(new AiExecutionLifecycle(
                    "detail", subtype, null, null, null, metadata).observation(scope));
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not observe AI lifecycle event {} for request {}",
                    subtype, requestId, failure);
        }
    }


    static AiChatSocketEvent socketEvent(ChatRequest request, long sequence, AiExecutionEvent event) {
        if ("assistant_update".equals(event.type())) {
            return AiChatSocketEvent.assistantUpdate(request.requestId(), request.conversationId(),
                    sequence, event.content());
        }
        if ("tool_call".equals(event.type())) {
            return AiChatSocketEvent.toolCall(request.requestId(), request.conversationId(), sequence,
                    event.subtype(), event.content(), event.toolCallId(), event.toolName(),
                    event.toolCallSequence() != null ? event.toolCallSequence() : sequence,
                    event.metadata());
        }
        if ("detail".equals(event.type())) {
            if ("change_approval_batch_required".equals(event.subtype())) {
                return AiChatSocketEvent.system(request.requestId(), request.conversationId(), sequence,
                        event.subtype(), event.content(), event.metadata());
            }
            if ("change_confirmation_required".equals(event.subtype())) {
                return AiChatSocketEvent.changeConfirmationRequired(request.requestId(),
                        request.conversationId(), sequence, event.content(), event.metadata());
            }
            if ("elicitation_required".equals(event.subtype())) {
                return AiChatSocketEvent.elicitationRequired(request.requestId(),
                        request.conversationId(), sequence, event.content(), event.metadata());
            }
            if ("guide".equals(event.subtype()) || "workflow_result".equals(event.subtype())) {
                return AiChatSocketEvent.system(request.requestId(), request.conversationId(), sequence,
                        event.subtype(), event.content(), event.metadata());
            }
            if (isWorkflowLifecycleEvent(event.subtype())) {
                return AiChatSocketEvent.system(request.requestId(), request.conversationId(), sequence,
                        event.subtype(), event.content(), event.metadata());
            }
            return AiChatSocketEvent.detail(request.requestId(), request.conversationId(), sequence,
                    event.subtype(), event.content(), event.metadata());
        }
        return AiChatSocketEvent.progress(request.requestId(), request.conversationId(), sequence, event.content());
    }

    private static boolean isWorkflowLifecycleEvent(String subtype) {
        return WORKFLOW_LIFECYCLE_EVENT_TYPES.contains(subtype);
    }

    private static boolean isRestResponseEvent(AiExecutionEvent event) {
        return "tool_call".equals(event.type()) || "detail".equals(event.type())
                && ("change_confirmation_required".equals(event.subtype())
                || "change_approval_batch_required".equals(event.subtype())
                || "change_approval_decision_accepted".equals(event.subtype())
                || "elicitation_required".equals(event.subtype())
                || "context_usage".equals(event.subtype())
                || "context_compacted".equals(event.subtype())
                || "provider_error".equals(event.subtype())
                || "provider_retry".equals(event.subtype())
                || "workflow_result".equals(event.subtype())
                || "guide".equals(event.subtype())
                || isWorkflowLifecycleEvent(event.subtype()));
    }

    private static boolean isRestLiveEvent(AiExecutionEvent event) {
        return "tool_call".equals(event.type())
                || "guide".equals(event.subtype())
                || "change_approval_batch_required".equals(event.subtype())
                || "change_approval_decision_accepted".equals(event.subtype())
                || "elicitation_required".equals(event.subtype())
                || "provider_error".equals(event.subtype())
                || "provider_retry".equals(event.subtype())
                || "workflow_result".equals(event.subtype())
                || isWorkflowLifecycleEvent(event.subtype());
    }

    static List<AiChatSocketEvent> orderedResponseEvents(List<AiChatSocketEvent> events) {
        return events.stream()
                .sorted(Comparator.comparing(AiChatSocketEvent::sequence,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();
    }
}
