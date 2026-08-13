package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailure;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Maps runtime-specific exceptions to stable SCORE activity failure semantics. */
@Component
public final class ScoreActivityFailureClassifier {

    public ScoreActivityFailureCode classify(Throwable failure) {
        if (failure instanceof ScoreActivityFailure activityFailure) {
            return activityFailure.failureCode();
        }
        if (failure instanceof AccessDeniedException) {
            return ScoreActivityFailureCode.ACCESS_DENIED;
        }
        if (failure instanceof IllegalArgumentException) {
            return ScoreActivityFailureCode.VALIDATION_ERROR;
        }
        return ScoreActivityFailureCode.INTERNAL_ERROR;
    }
}
