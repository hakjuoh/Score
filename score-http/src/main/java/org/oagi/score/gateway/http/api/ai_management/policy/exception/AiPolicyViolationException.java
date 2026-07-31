package org.oagi.score.gateway.http.api.ai_management.policy.exception;

public class AiPolicyViolationException extends RuntimeException {

    private final AiPolicyErrorCode code;

    public AiPolicyViolationException(AiPolicyErrorCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public AiPolicyErrorCode code() {
        return code;
    }
}
