package org.oagi.score.gateway.http.api.ai_management.model;

/** Persistent workflow preference parsed from a user request. */
public record AiPersistentWorkflowCommand(String activeWorkflow, Mode mode) {

    public String acknowledgement() {
        return switch (mode) {
            case ENABLE_AGENTS -> "Understood. I’ll use sub-agents for subsequent requests "
                    + "in this conversation. What would you like to know?";
            case DISABLE_AGENTS -> "Understood. I won’t use sub-agents for subsequent requests "
                    + "in this conversation. What would you like to know?";
            case AUTOMATIC -> "Understood. I’ll choose the workflow separately for each subsequent "
                    + "request in this conversation. What would you like to know?";
            case NAMED -> "Understood. I’ll use the " + activeWorkflow
                    + " workflow for subsequent requests in this conversation. What would you like to know?";
        };
    }

    public enum Mode {
        ENABLE_AGENTS, DISABLE_AGENTS, AUTOMATIC, NAMED
    }
}
