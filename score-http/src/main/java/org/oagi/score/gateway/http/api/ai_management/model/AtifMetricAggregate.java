package org.oagi.score.gateway.http.api.ai_management.model;

/** Measured token total together with the number of LLM calls it covers. */
public record AtifMetricAggregate(long partialTotal, int measuredLlmCalls) {

    public AtifMetricAggregate {
        if (partialTotal < 0) {
            throw new IllegalArgumentException("partialTotal must be non-negative");
        }
        if (measuredLlmCalls < 0) {
            throw new IllegalArgumentException("measuredLlmCalls must be non-negative");
        }
    }

    public boolean complete(int trackedLlmCalls, int untrackedLlmSteps) {
        return untrackedLlmSteps == 0 && measuredLlmCalls == trackedLlmCalls;
    }
}
