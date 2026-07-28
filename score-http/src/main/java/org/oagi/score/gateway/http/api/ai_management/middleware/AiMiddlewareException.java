package org.oagi.score.gateway.http.api.ai_management.middleware;

/** Fail-closed signal identifying the registered middleware and lifecycle hook that failed. */
public final class AiMiddlewareException extends RuntimeException {

    public AiMiddlewareException(String middlewareId, String hook, Throwable cause) {
        super("AI middleware '" + middlewareId + "' failed in " + hook + ".", cause);
    }
}
