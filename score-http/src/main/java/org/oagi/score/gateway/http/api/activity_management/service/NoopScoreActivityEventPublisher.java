package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;

/** Publisher used when activity collection is disabled. */
public final class NoopScoreActivityEventPublisher implements ScoreActivityEventPublisher {

    @Override
    public void publish(ScoreActivityEvent event) {
        // Activity history is optional.
    }

    @Override
    public void publishImmediately(ScoreActivityEvent event) {
        // Activity history is optional.
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
