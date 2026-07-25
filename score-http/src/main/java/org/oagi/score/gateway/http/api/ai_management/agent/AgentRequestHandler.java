package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;

/** Prepares the next turn without executing a model or a tool. */
@FunctionalInterface
public interface AgentRequestHandler {

    AgentRunRequest prepare(Agent agent, AgentWorkflowContext context);

    static AgentRequestHandler defaultRequest() {
        return (agent, context) -> new AgentRunRequest.Model(
                context.request().modelName(),
                agent.definition().instruction().render(),
                new AiMessage.User(context.request().prompt()),
                List.of(),
                context.executionScope(ExecutionScope.Purpose.USER_RESPONSE),
                context.observationContext());
    }
}
