package org.oagi.score.gateway.http.api.ai_management.controller.payload;

/** One-time exact or user-revised tool approval; authorization remains server-side. */
public record MutationConfirmation(String confirmationRequestId, String confirmationGrant,
                                   String toolName, String arguments,
                                   String approvalMode, String revisionPrompt) {

    public MutationConfirmation(String confirmationRequestId, String confirmationGrant) {
        this(confirmationRequestId, confirmationGrant, null, null, null, null);
    }

    public MutationConfirmation(String confirmationRequestId, String confirmationGrant,
                                String toolName, String arguments) {
        this(confirmationRequestId, confirmationGrant, toolName, arguments, "EXACT", null);
    }

    public boolean revised() {
        return "REVISED".equalsIgnoreCase(approvalMode);
    }
}
