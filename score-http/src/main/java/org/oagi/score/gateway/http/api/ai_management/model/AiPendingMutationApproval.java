package org.oagi.score.gateway.http.api.ai_management.model;

/** Exact guarded mutation retained by the active tool session while the user decides. */
public record AiPendingMutationApproval(
        AiMutationConfirmationNotice notice,
        String toolName,
        String arguments) {
}
