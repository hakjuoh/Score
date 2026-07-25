package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;

import java.util.Objects;

/** Declarative result returned by one {@code AgentRunner} call. */
public sealed interface AgentDecision permits AgentDecision.Complete,
        AgentDecision.Handoff, AgentDecision.Delegate {

    /** Finishes this Agent's assigned unit of work. */
    record Complete(AgentOutput result) implements AgentDecision {
        public Complete {
            Objects.requireNonNull(result, "result");
        }
    }

    /** Places another independent Agent in the current Workflow's call queue. */
    record Handoff(Agent.AgentId target, AiWorkflowFeedback feedback) implements AgentDecision {
        public Handoff {
            Objects.requireNonNull(target, "target");
        }

        public Handoff(Agent.AgentId target) {
            this(target, null);
        }
    }

    /** Places a recursively executable child Workflow in the current call queue. */
    record Delegate(AiWorkflowPlan workflow) implements AgentDecision {
        public Delegate {
            Objects.requireNonNull(workflow, "workflow");
        }
    }
}
