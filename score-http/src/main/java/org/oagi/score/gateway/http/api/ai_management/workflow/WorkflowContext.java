package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.service.AiChatExecutor;

import java.util.List;

/**
 * Immutable execution state passed between composed workflows. The concurrent flag
 * marks state that flows into simultaneously executing siblings, so leaf executors
 * can restrict what a non-exclusive execution is allowed to do.
 */
public record WorkflowContext(AiChatExecutor.Context executionContext,
                              List<WorkflowResult> upstreamResults,
                              boolean concurrent) {

    public WorkflowContext {
        if (executionContext == null) {
            throw new IllegalArgumentException("A workflow execution context is required.");
        }
        upstreamResults = upstreamResults != null
                ? List.copyOf(upstreamResults) : List.of();
    }

    public WorkflowContext(AiChatExecutor.Context executionContext,
                           List<WorkflowResult> upstreamResults) {
        this(executionContext, upstreamResults, false);
    }

    public static WorkflowContext root(AiChatExecutor.Context executionContext) {
        return new WorkflowContext(executionContext, List.of(), false);
    }

    public WorkflowContext withUpstream(List<WorkflowResult> results) {
        return new WorkflowContext(executionContext, results, concurrent);
    }

    public WorkflowContext concurrentBranch() {
        return concurrent ? this : new WorkflowContext(executionContext, upstreamResults, true);
    }
}
