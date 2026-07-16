package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record AiMutationConfirmationDecisionRequest(String decision, String revisionPrompt) {

    public AiMutationConfirmationDecisionRequest(String decision) {
        this(decision, null);
    }
}
