package org.oagi.score.gateway.http.api.activity_management.model;

import static java.util.Objects.requireNonNull;

/** A business exception carrying a stable, non-sensitive activity failure code. */
public final class ScoreActivityException extends RuntimeException implements ScoreActivityFailure {

    private final ScoreActivityFailureCode failureCode;

    public ScoreActivityException(ScoreActivityFailureCode failureCode, String message) {
        super(message);
        this.failureCode = requireNonNull(failureCode, "failureCode must not be null");
    }

    public ScoreActivityException(
            ScoreActivityFailureCode failureCode,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failureCode = requireNonNull(failureCode, "failureCode must not be null");
    }

    @Override
    public ScoreActivityFailureCode failureCode() {
        return failureCode;
    }

    public static ScoreActivityException validation(String message) {
        return new ScoreActivityException(ScoreActivityFailureCode.VALIDATION_ERROR, message);
    }

    public static ScoreActivityException targetNotFound(String message) {
        return new ScoreActivityException(ScoreActivityFailureCode.TARGET_NOT_FOUND, message);
    }

    public static ScoreActivityException invalidState(String message) {
        return new ScoreActivityException(ScoreActivityFailureCode.INVALID_STATE, message);
    }

    public static ScoreActivityException accessDenied(String message) {
        return new ScoreActivityException(ScoreActivityFailureCode.ACCESS_DENIED, message);
    }
}
