package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.List;
import java.util.Objects;

/** Protocol-neutral input shared by structural Workflow implementations. */
public record WorkflowInvocation(ExecutionScope scope, List<AiMessage> history,
                                 AiMessage.User request, AgentRunner agentRunner) {

    public WorkflowInvocation {
        Objects.requireNonNull(scope, "scope");
        history = history != null ? List.copyOf(history) : List.of();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(agentRunner, "agentRunner");
    }

    @FunctionalInterface
    public interface AgentRunner {
        AgentRunResult run(AgentInvocation invocation);
    }
}
