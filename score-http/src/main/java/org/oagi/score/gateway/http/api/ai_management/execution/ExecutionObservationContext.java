package org.oagi.score.gateway.http.api.ai_management.execution;

/**
 * Activates correlation context owned by an observation backend without exposing
 * that backend's SDK to execution and trajectory code.
 */
@FunctionalInterface
public interface ExecutionObservationContext {

    Activation makeToolCurrent(String requestId, String toolCallId);

    /** Starts a GenAI planning operation. Backends that do not emit telemetry return a no-op. */
    default Operation startPlan(String requestId, String agentName) {
        return Operation.noop();
    }

    static ExecutionObservationContext noop() {
        return (requestId, toolCallId) -> () -> { };
    }

    @FunctionalInterface
    interface Activation extends AutoCloseable {
        @Override
        void close();
    }

    interface Operation extends AutoCloseable {

        void fail(Throwable failure);

        void cancel();

        @Override
        void close();

        static Operation noop() {
            return NoopOperation.INSTANCE;
        }
    }

    enum NoopOperation implements Operation {
        INSTANCE;

        @Override public void fail(Throwable failure) { }
        @Override public void cancel() { }
        @Override public void close() { }
    }
}
