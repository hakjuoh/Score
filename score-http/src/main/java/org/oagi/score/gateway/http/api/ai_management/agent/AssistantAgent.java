package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * User-facing default Agent. It handles ordinary requests itself and hands
 * explicitly delegated work to the Planner Agent.
 */
@Component("connectcenter-assistant")
public final class AssistantAgent implements WorkflowAgent {

    public static final AgentId ASSISTANT_ID = new AgentId("connectcenter-assistant");
    public static final AgentId PLANNER_ID = new AgentId("workflow-planner");

    private final AiAgentCatalog agents;
    private final ObjectProvider<AiChatExecutor> executors;

    @Autowired
    public AssistantAgent(AiAgentCatalog agents,
                          ObjectProvider<AiChatExecutor> executors) {
        this.agents = agents;
        this.executors = executors;
    }

    public AssistantAgent(AiAgentCatalog agents) {
        this(agents, null);
    }

    @Override
    public AgentDefinition definition() {
        return agents.configuredRootDefinition();
    }

    public Instruction instruction(Map<String, ?> parameters) {
        return definition().instruction().render(parameters);
    }

    @Override
    public AgentId callId() {
        return ASSISTANT_ID;
    }

    @Override
    public AgentDecision execute(AgentWorkflowContext context) {
        if (delegationRequested(context)) {
            return new AgentDecision.Handoff(PLANNER_ID);
        }
        AiChatExecutor executor = executors != null ? executors.getIfAvailable() : null;
        if (executor == null) {
            throw new IllegalStateException("No AI chat executor is configured.");
        }
        return new AgentDecision.Complete(executor.execute(
                context.execution().withWorkflowObservationContext(
                        context.observationContext())));
    }

    private boolean delegationRequested(AgentWorkflowContext context) {
        return context.request().delegationRequested();
    }
}
