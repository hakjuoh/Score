package org.oagi.score.gateway.http.api.ai_management.model;

/** Server-only decision material used to redeem or reject one pending mutation. */
public record AiMutationApprovalResolution(
        String confirmationRequestId,
        Decision decision,
        String confirmationGrant) {

    public enum Decision {
        APPROVE,
        DENY
    }

    public boolean approved() {
        return decision == Decision.APPROVE && confirmationGrant != null;
    }
}
