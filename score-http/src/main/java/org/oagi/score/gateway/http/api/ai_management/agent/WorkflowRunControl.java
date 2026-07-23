package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;

import java.util.function.Supplier;

/** Request-scoped cancellation/deadline and usage settlement port shared by recursive Agents. */
public interface WorkflowRunControl {

    WorkflowRunControl NOOP = new WorkflowRunControl() {
        @Override public void checkpoint() { }
        @Override public void recordUsage(AiUsageSnapshot usage) { }
        @Override public void registerUsage(Supplier<AiUsageSnapshot> usage,
                                            Runnable lateWriteFence) { }
    };

    void checkpoint();

    void recordUsage(AiUsageSnapshot usage);

    /** Registers a live usage source and a fence that suppresses post-terminal writes. */
    void registerUsage(Supplier<AiUsageSnapshot> usage, Runnable lateWriteFence);
}
