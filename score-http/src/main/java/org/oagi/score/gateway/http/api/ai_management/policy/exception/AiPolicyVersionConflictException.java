package org.oagi.score.gateway.http.api.ai_management.policy.exception;

public class AiPolicyVersionConflictException extends AiPolicyViolationException {
    public AiPolicyVersionConflictException() {
        super(AiPolicyErrorCode.AI_POLICY_VERSION_CONFLICT,
                "The AI policy was changed by another administrator. Reload and try again.");
    }
}
