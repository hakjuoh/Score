package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.Objects;
import java.util.function.Predicate;

/** Authoritative structural and address validation for model-authored Workflows. */
public final class WorkflowPlanValidator {

    public static final int MAXIMUM_DEPTH = 8;
    public static final int MAXIMUM_MEMBERS = 32;

    public void validate(AiWorkflowPlan plan, int maximumAgents,
                         Predicate<String> assignableAgent) {
        Objects.requireNonNull(plan, "workflow");
        if (maximumAgents < 1) {
            throw new IllegalArgumentException("Maximum Agent calls must be positive.");
        }
        Objects.requireNonNull(assignableAgent, "assignableAgent");
        Bounds bounds = new Bounds();
        validate(plan.root(), 1, maximumAgents, assignableAgent, bounds);
    }

    private void validate(AiWorkflowPlan.WorkflowDefinition workflow, int depth,
                          int maximumAgents, Predicate<String> assignableAgent,
                          Bounds bounds) {
        if (depth > MAXIMUM_DEPTH) {
            throw new IllegalArgumentException("Workflow nesting is too deep.");
        }
        for (AiWorkflowPlan.Member member : workflow.members()) {
            if (++bounds.members > MAXIMUM_MEMBERS) {
                throw new IllegalArgumentException("Workflow has too many members.");
            }
            if (member.agent() != null) {
                if (++bounds.agentCalls > maximumAgents) {
                    throw new IllegalArgumentException("Workflow exceeds the Agent call limit.");
                }
                if (!assignableAgent.test(member.agent().agentId())) {
                    throw new IllegalArgumentException(
                            "Agent is not assignable: " + member.agent().agentId());
                }
            } else {
                validate(member.workflow(), depth + 1, maximumAgents,
                        assignableAgent, bounds);
            }
        }
    }

    private static final class Bounds {
        private int members;
        private int agentCalls;
    }
}
