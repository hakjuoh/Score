package org.oagi.score.gateway.http.api.activity_management.model;

/**
 * Identifies a domain state failure while retaining legacy {@link IllegalStateException}
 * HTTP handling.
 */
public final class ScoreActivityInvalidStateException extends IllegalStateException
        implements ScoreActivityFailure {

    public ScoreActivityInvalidStateException(String message) {
        super(message);
    }

    @Override
    public ScoreActivityFailureCode failureCode() {
        return ScoreActivityFailureCode.INVALID_STATE;
    }
}
