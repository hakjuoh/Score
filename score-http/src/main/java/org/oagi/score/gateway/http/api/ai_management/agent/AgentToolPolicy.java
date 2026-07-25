package org.oagi.score.gateway.http.api.ai_management.agent;

/** Provider-neutral tool authority carried by an Agent execution context. */
public enum AgentToolPolicy {
    NONE,
    READ_ONLY,
    FULL;

    /** Reduces a child assignment to the authority granted by its parent turn. */
    public static AgentToolPolicy restrict(AgentToolPolicy parent, boolean parentToolsEnabled,
                                           boolean fullRequested, boolean noneRequested) {
        if (!parentToolsEnabled || parent == null || parent == NONE || noneRequested) {
            return NONE;
        }
        return fullRequested && parent == FULL ? FULL : READ_ONLY;
    }
}
