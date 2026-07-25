package org.oagi.score.gateway.http.api.ai_management.agent;

/** Resolves the tools visible to one Agent turn. */
@FunctionalInterface
public interface AgentToolHandler {

    AgentToolBinding resolve(Agent agent, AgentWorkflowContext context);

    /** Gives the Agent an explicit empty tool set and a disabled gateway. */
    static AgentToolHandler none() {
        return (agent, context) -> AgentToolBinding.none();
    }

    /** Keeps the transport's already-authorized Tool registry for this Agent. */
    static AgentToolHandler transport() {
        return (agent, context) -> AgentToolBinding.inheritTransport();
    }
}
