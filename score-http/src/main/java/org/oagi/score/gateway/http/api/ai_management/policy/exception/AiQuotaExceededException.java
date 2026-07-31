package org.oagi.score.gateway.http.api.ai_management.policy.exception;

import java.time.Duration;

public class AiQuotaExceededException extends AiPolicyViolationException {

    private final Duration retryAfter;

    public AiQuotaExceededException(AiPolicyErrorCode code, String message, Duration retryAfter) {
        super(code, message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
