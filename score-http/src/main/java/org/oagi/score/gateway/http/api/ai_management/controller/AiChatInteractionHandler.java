package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancelRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationRestoreRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiElicitationDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Handles request-local WebSocket interactions independently of endpoint routing. */
final class AiChatInteractionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatInteractionHandler.class);

    private final ChatService chatService;
    private final AiRequestRegistry requests;
    private final AiElicitationService elicitations;
    private final AiChangeApprovalCoordinator changeApprovals;
    private final ExecutionObserver observer;
    private final SimpMessagingTemplate messaging;

    AiChatInteractionHandler(ChatService chatService, AiRequestRegistry requests,
                             AiElicitationService elicitations,
                             AiChangeApprovalCoordinator changeApprovals,
                             ExecutionObserver observer, SimpMessagingTemplate messaging) {
        this.chatService = chatService;
        this.requests = requests;
        this.elicitations = elicitations;
        this.changeApprovals = changeApprovals;
        this.observer = observer != null ? observer : ExecutionObserver.noop();
        this.messaging = messaging;
    }

    void restore(ScoreUser requester, AiConversationRestoreRequest request) {
        String destination = queue(request.requestId());
        try {
            ChatConversationDetails details = chatService.conversation(
                    requester, request.conversationId());
            Map<String, Object> metadata = restoreMetadata(request, details);
            send(requester, destination, AiChatSocketEvent.system(request.requestId(),
                    request.conversationId(), null, "accepted", "Restoring conversation.", metadata));
            send(requester, destination, AiChatSocketEvent.historyStart(request.requestId(),
                    request.conversationId(), details.title(), metadata));
            details.messages().forEach(message -> send(requester, destination,
                    AiChatSocketEvent.historyMessage(request.requestId(),
                            request.conversationId(), message, metadata)));
            send(requester, destination, AiChatSocketEvent.historyFinal(request.requestId(),
                    request.conversationId(), metadata));
        } catch (RuntimeException exception) {
            Map<String, Object> metadata = Map.of(
                    "restoreToken", request.restoreToken(),
                    "restoreSequence", request.restoreSequence());
            send(requester, destination, AiChatSocketEvent.system(request.requestId(),
                    request.conversationId(), null, "error",
                    AiChatTransport.safeMessage(exception), metadata));
        }
    }

    void cancel(ScoreUser requester, AiCancelRequest command) {
        String cancellationId = StringUtils.hasText(command.cancellationRequestId())
                ? command.cancellationRequestId() : UUID.randomUUID().toString();
        AiCancellationResponse response = requests.cancel(command.requestId(), cancellationId,
                command.conversationId(), command.expectedGeneration(), requester);
        if (response.acknowledged()) {
            elicitations.cancelRequest(command.requestId());
            if (changeApprovals != null) changeApprovals.cancelRequest(command.requestId());
        }
        AiChatSocketEvent event = "CANCELLING".equals(response.status())
                ? AiChatSocketEvent.cancellationAcknowledged(command.requestId(),
                response.conversationId(), response.generation(),
                response.lifecycleEventSequence(), cancellationId)
                : AiChatSocketEvent.system(command.requestId(), response.conversationId(),
                response.lifecycleEventSequence(), "cancellation_current_status",
                AiChatTransport.cancellationDispositionMessage(response),
                Map.of("generation", response.generation(), "status", response.status(),
                        "disposition", response.disposition(), "terminal", response.terminal(),
                        "acknowledged", response.acknowledged(),
                        "cancellationRequestId", cancellationId));
        send(requester, queue(command.requestId()), event);
    }

    void decideElicitation(ScoreUser requester, AiElicitationDecisionRequest command) {
        try {
            elicitations.decide(requester, command.requestId(), command.conversationId(),
                    command.elicitationId(), command.generation(), command.action(), command.content());
            observe(requester, command.requestId(), command.conversationId(), command.generation(),
                    "elicitation_decision_accepted", Map.of("elicitationId", command.elicitationId()));
            send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                    command.requestId(), command.conversationId(), null,
                    "elicitation_decision_accepted", "Your response was sent to the assistant.",
                    Map.of("elicitationId", command.elicitationId())));
        } catch (RuntimeException exception) {
            observe(requester, command.requestId(), command.conversationId(), command.generation(),
                    "elicitation_decision_rejected", Map.of("elicitationId", command.elicitationId()));
            send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                    command.requestId(), command.conversationId(), null,
                    "elicitation_decision_rejected",
                    "The assistant could not accept that response. Please try again.",
                    Map.of("elicitationId", command.elicitationId())));
        }
    }

    void decideChangeApproval(ScoreUser requester, AiChangeApprovalDecisionRequest command) {
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
                                + " and denied " + denied + ". Continuing the active request.",
                        Map.of("batchId", replay.batchId(), "replayed", true)));
            }
        } catch (RuntimeException exception) {
            if (command == null || !StringUtils.hasText(command.requestId())
                    || !StringUtils.hasText(command.conversationId())
                    || !StringUtils.hasText(command.batchId())) throw exception;
            send(requester, queue(command.requestId()), AiChatSocketEvent.system(
                    command.requestId(), command.conversationId(), null,
                    "change_approval_decision_rejected",
                    "The assistant could not accept that approval decision. Please try again.",
                    Map.of("batchId", command.batchId())));
        }
    }

    void send(ScoreUser requester, String destination, AiChatSocketEvent event) {
        messaging.convertAndSendToUser(requester.username(), destination, event);
    }

    static String queue(String requestId) {
        return "/queue/ai/chat/" + requestId;
    }

    private Map<String, Object> restoreMetadata(AiConversationRestoreRequest request,
                                                ChatConversationDetails details) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("restoreToken", request.restoreToken());
        metadata.put("restoreSequence", request.restoreSequence());
        metadata.put("modelName", details.modelName());
        metadata.put("reasoningEffort", details.reasoningEffort());
        metadata.put("permissionMode", details.permissionMode());
        if (StringUtils.hasText(details.activeWorkflow())) {
            metadata.put("activeWorkflow", details.activeWorkflow());
        }
        if (details.contextUsage() != null) metadata.put("contextUsage", details.contextUsage());
        return metadata;
    }

    private void observe(ScoreUser requester, String requestId, String conversationId,
                         Long generation, String subtype, Map<String, Object> metadata) {
        if (!StringUtils.hasText(requestId) || !StringUtils.hasText(conversationId)
                || generation == null || generation <= 0L) return;
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && StringUtils.hasText(requester.username())
                ? requester.username() : "unknown";
        try {
            ExecutionScope scope = new ExecutionScope(requestId, conversationId, requesterId,
                    generation, ExecutionScope.Purpose.USER_RESPONSE, List.of());
            observer.observe(new AiExecutionLifecycle(
                    "detail", subtype, null, null, null, metadata).observation(scope));
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not observe AI lifecycle event {} for request {}",
                    subtype, requestId, failure);
        }
    }
}
