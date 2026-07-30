package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

/** One root-scoped decision covering every item in a displayed approval batch. */
public record AiChangeApprovalDecisionRequest(
        String requestId,
        String conversationId,
        String batchId,
        List<ItemDecision> decisions) {

    public AiChangeApprovalDecisionRequest {
        decisions = decisions != null ? List.copyOf(decisions) : List.of();
    }

    public record ItemDecision(String confirmationRequestId, String decision) {
    }
}
