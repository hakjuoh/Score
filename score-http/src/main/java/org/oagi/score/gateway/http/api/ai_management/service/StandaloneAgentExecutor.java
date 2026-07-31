package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.WorkflowRequestAdapter;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Executes single-Agent support operations under the request cancellation/activity lease. */
final class StandaloneAgentExecutor {

    private final AgentRunner agentRunner;
    private final AiRequestRegistry requests;

    StandaloneAgentExecutor(AgentRunner agentRunner, AiRequestRegistry requests) {
        this.agentRunner = agentRunner;
        this.requests = requests;
    }

    AgentOutput run(Agent agent, ChatExecutionContext execution) {
        AgentWorkflowContext workflowContext = AgentWorkflowContext.root(execution,
                WorkflowRequestAdapter.from(execution), 1,
                activityControl(execution.requestId()));
        AgentRunner runner = Objects.requireNonNull(agentRunner,
                "The shared AgentRunner is required for standalone Agent execution.");
        AgentDecision decision = runner.run(agent, workflowContext);
        if (!(decision instanceof AgentDecision.Complete complete)) {
            throw new IllegalStateException(
                    "Standalone Agent did not return a completed result.");
        }
        return complete.result();
    }

    private WorkflowRunControl activityControl(String requestId) {
        if (requests == null) return WorkflowRunControl.NOOP;
        return WorkflowRunControl.activityOnly(() -> {
            if (requests.shouldDiscardResult(requestId)) {
                throw new CancellationException("The assistant request was interrupted.");
            }
        }, () -> requests.progress(requestId));
    }
}
