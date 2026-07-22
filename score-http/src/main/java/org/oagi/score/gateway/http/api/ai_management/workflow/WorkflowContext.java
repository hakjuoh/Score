package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.List;

/**
 * Immutable execution state passed between composed workflows. The concurrent flag
 * marks state that flows into simultaneously executing siblings, so leaf executors
 * can restrict what a non-exclusive execution is allowed to do.
 */
public record WorkflowContext(WorkflowInvocation invocation,
                              List<WorkflowResult> upstreamResults,
                              boolean concurrent) {

    public WorkflowContext {
        if (invocation == null) {
            throw new IllegalArgumentException("A Workflow invocation is required.");
        }
        upstreamResults = upstreamResults != null
                ? List.copyOf(upstreamResults) : List.of();
    }

    public WorkflowContext(WorkflowInvocation invocation,
                           List<WorkflowResult> upstreamResults) {
        this(invocation, upstreamResults, false);
    }

    public static WorkflowContext root(WorkflowInvocation invocation) {
        return new WorkflowContext(invocation, List.of(), false);
    }

    public WorkflowContext withUpstream(List<WorkflowResult> results) {
        return new WorkflowContext(invocation, results, concurrent);
    }

    public WorkflowContext concurrentBranch() {
        return concurrent ? this : new WorkflowContext(invocation, upstreamResults, true);
    }
}
