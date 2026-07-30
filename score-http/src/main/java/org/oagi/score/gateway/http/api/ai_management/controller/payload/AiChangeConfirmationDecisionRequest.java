package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record AiChangeConfirmationDecisionRequest(String decision, String revisionPrompt) {

    public AiChangeConfirmationDecisionRequest(String decision) {
        this(decision, null);
    }
}
