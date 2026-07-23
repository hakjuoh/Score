package org.oagi.score.gateway.http.api.ai_management.execution;

/**
 * Activates correlation context owned by an observation backend without exposing
 * that backend's SDK to execution and trajectory code.
 */
@FunctionalInterface
public interface ExecutionObservationContext {

    Activation makeToolCurrent(String requestId, String toolCallId);

    static ExecutionObservationContext noop() {
        return (requestId, toolCallId) -> () -> { };
    }

    @FunctionalInterface
    interface Activation extends AutoCloseable {
        @Override
        void close();
    }
}
