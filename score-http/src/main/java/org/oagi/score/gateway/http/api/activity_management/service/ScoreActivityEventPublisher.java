package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;

/**
 * Application port used by SCORE features to publish user activity without knowing its destination.
 * Implementations must isolate scheduling and destination failures from the user operation.
 */
public interface ScoreActivityEventPublisher extends AutoCloseable {

    void publish(ScoreActivityEvent event);

    /**
     * Enqueues a failed-attempt event even when the surrounding transaction is expected to roll
     * back. This is still best-effort and never performs destination I/O on the caller thread.
     */
    void publishImmediately(ScoreActivityEvent event);

    default boolean isEnabled() {
        return true;
    }

    @Override
    default void close() {
        // Most publishers do not own resources.
    }
}
