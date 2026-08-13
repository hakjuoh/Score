package org.oagi.score.gateway.http.api.activity_management.model;

/** Marks exceptions that carry a stable activity failure code across runtime-specific hierarchies. */
public interface ScoreActivityFailure {

    ScoreActivityFailureCode failureCode();
}
