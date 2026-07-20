package org.oagi.score.gateway.http.api.ai_management.model;

/** Settled result from one delegated worker. */
public record AiMultiAgentWorkerResult(
        int ordinal,
        AiWorkflowPlan.Task task,
        AiAgentDefinition definition,
        boolean successful,
        String answer) {
}
