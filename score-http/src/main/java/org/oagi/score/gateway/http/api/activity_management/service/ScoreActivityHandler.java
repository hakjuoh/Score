package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;

/** Creates an isolated per-invocation activity lifecycle for an annotated operation. */
public interface ScoreActivityHandler {

    boolean supports(ScoreActivityInvocation invocation);

    ScoreActivityExecution start(ScoreActivityInvocation invocation);
}
