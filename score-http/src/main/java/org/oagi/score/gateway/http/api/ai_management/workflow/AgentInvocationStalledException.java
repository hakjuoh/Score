package org.oagi.score.gateway.http.api.ai_management.workflow;

/** Raised after one Agent invocation makes no observable progress for its lease window. */
final class AgentInvocationStalledException extends IllegalStateException {

    AgentInvocationStalledException(String agentId) {
        super("Agent " + agentId
                + " made no observable progress before its inactivity lease expired.");
    }
}
