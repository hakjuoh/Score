package org.oagi.score.gateway.http.api.ai_management.agent;

/**
 * An Agent that can participate in a Workflow without knowing how the Workflow
 * schedules or observes its calls.
 */
public interface WorkflowAgent extends Agent {

    /** Stable queue address; an Agent's configured runtime identity may differ. */
    default AgentId callId() {
        return id();
    }

    /**
     * Whether a model-authored Workflow may address this Agent as a worker.
     * Control-plane Agents are deliberately not assignable.
     */
    default boolean assignable() {
        return false;
    }

    AgentDecision execute(AgentWorkflowContext context);
}
