package org.oagi.score.gateway.http.api.ai_management.model;

/** Exact guarded change retained by the active tool session while the user decides. */
public record AiPendingChangeApproval(
        AiChangeConfirmationNotice notice,
        String toolName,
        String arguments) {
}
