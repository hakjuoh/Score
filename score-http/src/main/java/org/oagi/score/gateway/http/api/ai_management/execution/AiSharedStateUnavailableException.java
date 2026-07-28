package org.oagi.score.gateway.http.api.ai_management.execution;

/**
 * Signals that the shared AI request state could not be read or written because
 * its distributed lock stayed unavailable. Callers must fail fast instead of
 * waiting indefinitely so a terminal outcome still reaches the web client.
 */
public class AiSharedStateUnavailableException extends IllegalStateException {

    public AiSharedStateUnavailableException(String message) {
        super(message);
    }

    public AiSharedStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
