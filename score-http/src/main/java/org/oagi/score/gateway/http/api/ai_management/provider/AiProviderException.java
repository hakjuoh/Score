package org.oagi.score.gateway.http.api.ai_management.provider;

import org.springframework.util.StringUtils;

/**
 * A model-provider failure that the retry loop could not recover from, carrying the
 * provider's own human-readable message for direct display to the user. The original
 * provider exception remains the cause so diagnostics keep the real failure class.
 */
public final class AiProviderException extends RuntimeException {

    private final AiProviderFailure failure;
    private final int attempts;
    private final boolean mutationApplied;

    public AiProviderException(AiProviderFailure failure, int attempts, Throwable cause) {
        this(failure, attempts, false, cause);
    }

    public AiProviderException(AiProviderFailure failure, int attempts,
                               boolean mutationApplied, Throwable cause) {
        super(userMessage(failure, attempts, mutationApplied), cause);
        this.failure = failure;
        this.attempts = attempts;
        this.mutationApplied = mutationApplied;
    }

    public AiProviderFailure failure() {
        return failure;
    }

    public int attempts() {
        return attempts;
    }

    /** Whether the failed attempt had already executed a data-changing tool. */
    public boolean mutationApplied() {
        return mutationApplied;
    }

    private static String userMessage(AiProviderFailure failure, int attempts,
                                      boolean mutationApplied) {
        String detail = StringUtils.hasText(failure.message())
                ? failure.message()
                : "The model provider could not complete the request.";
        if (attempts > 1) {
            detail = detail + " (failed after " + attempts + " attempts)";
        }
        // Without this caveat a transient-looking failure invites a resend that
        // would repeat the data change the retry fence refused to replay.
        return mutationApplied
                ? detail + " Data changes that already completed remain applied."
                : detail;
    }
}
