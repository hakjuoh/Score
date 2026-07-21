package org.oagi.score.gateway.http.api.ai_management.model;

/** Structured stop/continue decision produced after one workflow execution. */
public record AiWorkflowEvaluation(Decision decision, String feedback,
                                   String nextObjective) {

    public enum Decision {
        COMPLETE,
        CONTINUE
    }

    public boolean complete() {
        return decision == Decision.COMPLETE;
    }
}
