package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settles rejected requests without allowing one cleanup failure to suppress the rest. */
final class AiChatRequestFinalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatRequestFinalizer.class);

    private final ChatService chatService;
    private final AiRequestRegistry requests;
    private final AiChangeApprovalCoordinator changeApprovals;

    AiChatRequestFinalizer(ChatService chatService, AiRequestRegistry requests,
                           AiChangeApprovalCoordinator changeApprovals) {
        this.chatService = chatService;
        this.requests = requests;
        this.changeApprovals = changeApprovals;
    }

    void finishBeforeExecution(AiRequestRegistry.Entry entry, ChatRequest request,
                               ScoreUser requester, ScoreAiObservability.Turn observation,
                               String reason, RuntimeException failure) {
        String status;
        try {
            status = requests.finish(entry, failure);
        } catch (RuntimeException finishFailure) {
            status = "FAILED";
            suppress(failure, finishFailure);
            LOGGER.error("Could not settle rejected AI request {}", entry.requestId(),
                    finishFailure);
        }
        String terminalStatus = status;
        step(entry, failure, "clear its approval state", () -> clear(request.requestId()));
        step(entry, failure, "record its admission rejection",
                () -> observation.admissionRejected(reason));
        step(entry, failure, "persist its trajectory failure",
                () -> chatService.recordFailure(request, requester,
                        AiChatTransport.terminalMessage(terminalStatus, failure),
                        AiChatTransport.failureClass(failure), entry.generation()));
        step(entry, failure, "complete its observation",
                () -> observation.complete("admission_rejected", failure));
    }

    void clear(String requestId) {
        if (changeApprovals != null) changeApprovals.cancelRequest(requestId);
        chatService.clearPolicySnapshot(requestId);
    }

    String cancellationRequestId(AiRequestRegistry.Entry entry) {
        try {
            return requests.cancellationRequestId(entry);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not read the cancellation correlation of AI request {}",
                    entry.requestId(), failure);
            return null;
        }
    }

    private void step(AiRequestRegistry.Entry entry, RuntimeException failure,
                      String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException stepFailure) {
            suppress(failure, stepFailure);
            LOGGER.error("Could not {} for rejected AI request {}", operation, entry.requestId(),
                    stepFailure);
        }
    }

    private void suppress(RuntimeException failure, RuntimeException secondary) {
        if (secondary != failure) failure.addSuppressed(secondary);
    }
}
