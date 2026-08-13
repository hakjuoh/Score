package org.oagi.score.gateway.http.api.activity_management.model;

/** Stable, non-sensitive failure categories shared by SCORE activity producers. */
public enum ScoreActivityFailureCode {
    ACCESS_DENIED,
    VALIDATION_ERROR,
    INVALID_STATE,
    TARGET_NOT_FOUND,
    NOT_APPLIED,
    BATCH_ROLLED_BACK,
    INTERNAL_ERROR
}
