package org.oagi.score.gateway.http.api.ai_management.provider;

import java.time.Duration;

/**
 * Classification of one model-provider failure: the provider's own human-readable
 * message, whether another attempt can succeed, and the provider-directed wait.
 *
 * @param failureClass fully qualified class of the provider exception
 * @param statusCode HTTP status of the provider response, or 0 when none applies
 * @param message the provider's human-readable error message, bounded and sanitized
 * @param retryable whether the failure is transient per the provider's own guidance
 * @param retryAfter the provider-directed minimum wait before the next attempt, or null
 */
public record AiProviderFailure(String failureClass, int statusCode, String message,
                                boolean retryable, Duration retryAfter) {
}
