package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;

import java.util.List;

/** Maps one prepared service invocation to its eventual success or failure events. */
public interface ScoreActivityExecution {

    /** Whether a normally returned result represents an applied user action. */
    default boolean isSuccessful(Object result) {
        return !(result instanceof Boolean booleanResult) || booleanResult;
    }

    List<ScoreActivityEvent> succeeded(Object result);

    List<ScoreActivityEvent> failed(Throwable failure);
}
