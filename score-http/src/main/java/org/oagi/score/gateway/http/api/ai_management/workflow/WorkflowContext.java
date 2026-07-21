package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;

import java.util.List;

/**
 * Immutable execution state passed between composed workflows. The concurrent flag
 * marks state that flows into simultaneously executing siblings, so leaf executors
 * can restrict what a non-exclusive execution is allowed to do.
 */
public record WorkflowContext(AiRuntime.Context runtimeContext,
                              List<WorkflowResult> upstreamResults,
                              boolean concurrent) {

    public WorkflowContext {
        if (runtimeContext == null) {
            throw new IllegalArgumentException("A workflow runtime context is required.");
        }
        upstreamResults = upstreamResults != null
                ? List.copyOf(upstreamResults) : List.of();
    }

    public WorkflowContext(AiRuntime.Context runtimeContext,
                           List<WorkflowResult> upstreamResults) {
        this(runtimeContext, upstreamResults, false);
    }

    public static WorkflowContext root(AiRuntime.Context runtimeContext) {
        return new WorkflowContext(runtimeContext, List.of(), false);
    }

    public WorkflowContext withUpstream(List<WorkflowResult> results) {
        return new WorkflowContext(runtimeContext, results, concurrent);
    }

    public WorkflowContext concurrentBranch() {
        return concurrent ? this : new WorkflowContext(runtimeContext, upstreamResults, true);
    }
}
