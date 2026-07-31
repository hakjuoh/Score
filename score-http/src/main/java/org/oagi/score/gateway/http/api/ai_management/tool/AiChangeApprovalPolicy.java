package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangePermissionMode;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeRisk;
import org.oagi.score.gateway.http.common.model.ScoreUser;

/** Decides whether one data-changing tool invocation needs explicit user approval. */
final class AiChangeApprovalPolicy {

    private final AiChangeOwnershipPolicy ownership;

    AiChangeApprovalPolicy(AiChangeOwnershipPolicy ownership) {
        this.ownership = ownership;
    }

    boolean requiresApproval(String requestedPermissionMode, ScoreUser requester,
                             String toolName, String arguments) {
        AiChangePermissionMode permissionMode =
                AiChangePermissionMode.resolve(requestedPermissionMode);
        AiChangeRisk risk = AiChangeRiskCatalog.ruleOf(toolName).risk();
        if (permissionMode.automaticallyAllows(risk)) return false;
        return !permissionMode.requiresOwnershipCheck(risk)
                || !ownership.requesterOwnsTarget(requester, toolName, arguments);
    }
}
