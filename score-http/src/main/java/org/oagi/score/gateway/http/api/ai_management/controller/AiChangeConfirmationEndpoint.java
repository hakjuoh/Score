package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeConfirmationDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeConfirmationDecisionResponse;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeDecision;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

/** Serializes REST confirmation decisions against active requests and approval reservations. */
final class AiChangeConfirmationEndpoint {

    private final AiRequestRegistry requests;
    private final AiChangeConfirmationService confirmations;
    private final AiChangeApprovalCoordinator approvals;

    AiChangeConfirmationEndpoint(AiRequestRegistry requests,
                                 AiChangeConfirmationService confirmations,
                                 AiChangeApprovalCoordinator approvals) {
        this.requests = requests;
        this.confirmations = confirmations;
        this.approvals = approvals;
    }

    ResponseEntity<AiChangeConfirmationDecisionResponse> decide(
            ScoreUser requester, String conversationId, String confirmationRequestId,
            AiChangeConfirmationDecisionRequest request) {
        String sourceRequestId = confirmations.sourceRequestId(
                requester, conversationId, confirmationRequestId);
        Supplier<ResponseEntity<AiChangeConfirmationDecisionResponse>> decision =
                () -> requests.whileRequestAndConversationIdle(
                        sourceRequestId, conversationId, () -> {
                            AiChangeDecision result = confirmations.decide(
                                    requester, conversationId, confirmationRequestId,
                                    request != null ? request.decision() : null,
                                    request != null ? request.revisionPrompt() : null);
                            return ResponseEntity.status(result.status()).body(
                                    confirmations.bindConversation(result.response(), conversationId));
                        });
        return approvals != null
                ? approvals.whileConfirmationUnreserved(confirmationRequestId, decision)
                : decision.get();
    }
}
