package org.oagi.score.gateway.http.api.ai_management.execution;

/**
 * Receives canonical execution events after their identity, request sequence, and timestamp
 * have been fixed by {@link ExecutionEventPublisher}.
 */
@FunctionalInterface
public interface ExecutionEventListener {

    void onEvent(ExecutionObservation event);

    /**
     * Causal listeners establish in-process state needed by the code that runs immediately after
     * publication. External I/O listeners can opt out and are then drained asynchronously while
     * retaining the canonical per-request order.
     */
    default boolean causal() {
        return true;
    }

    /** Allows a statically configured optional sink to disappear from the hot path entirely. */
    default boolean enabled() {
        return true;
    }
}
