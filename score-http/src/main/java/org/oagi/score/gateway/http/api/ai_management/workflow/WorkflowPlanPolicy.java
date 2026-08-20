package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.DelegationIntent;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.WorkflowPlanValidator;

import java.util.Objects;

/** Centralizes workflow-plan admission and assignment tool restrictions. */
final class WorkflowPlanPolicy {

    private final AgentRunner agents;
    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();

    WorkflowPlanPolicy(AgentRunner agents) {
        this.agents = Objects.requireNonNull(agents, "agents");
    }

    void validate(AiWorkflowPlan plan, int maximumAgents) {
        validator.validate(plan, maximumAgents, agents::assignable);
    }

    void validate(AiWorkflowPlan plan, AgentWorkflowContext.Request request) {
        if (request.explicitDelegationRequested()) {
            int requiredAgents = DelegationIntent.boundedRequestedAgentCount(
                    request.prompt(), request.maximumAgents());
            validator.validateExactAgentCalls(plan, requiredAgents, agents::assignable);
            return;
        }
        validate(plan, request.maximumAgents());
    }

    AgentToolPolicy assignmentTools(AgentExecutionContext parent,
                                    AiWorkflowPlan.ToolAccess requested) {
        return AgentToolPolicy.restrict(parent.toolPolicy(), parent.toolsEnabled(),
                requested == AiWorkflowPlan.ToolAccess.FULL,
                requested == AiWorkflowPlan.ToolAccess.NONE);
    }
}
