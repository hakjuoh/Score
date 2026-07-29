package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;

import java.util.Objects;
import java.util.function.Supplier;

/** Request-scoped cancellation, activity lease, and usage settlement port shared by Agents. */
public interface WorkflowRunControl {

    WorkflowRunControl NOOP = new WorkflowRunControl() {
        @Override public void checkpoint() { }
        @Override public void progress() { }
        @Override public void recordUsage(AiUsageSnapshot usage) { }
        @Override public void registerUsage(Supplier<AiUsageSnapshot> usage,
                                            Runnable lateWriteFence) { }
    };

    /** Creates a progress/cancellation control for standalone Agent calls with no usage budget. */
    static WorkflowRunControl activityOnly(Runnable checkpoint, Runnable progress) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(progress, "progress");
        return new WorkflowRunControl() {
            @Override public void checkpoint() { checkpoint.run(); }
            @Override public void progress() { checkpoint.run(); progress.run(); }
            @Override public void recordUsage(AiUsageSnapshot usage) { }
            @Override public void registerUsage(Supplier<AiUsageSnapshot> usage,
                                                Runnable lateWriteFence) { }
        };
    }

    void checkpoint();

    /** Records observable forward progress for the current Agent invocation. */
    default void progress() {
        checkpoint();
    }

    /** Protects a known in-flight operation from an invocation inactivity stop. */
    default void definiteActivityStarted() {
        progress();
    }

    /** Starts a fresh inactivity window after the known operation finishes. */
    default void definiteActivityFinished() {
        progress();
    }

    void recordUsage(AiUsageSnapshot usage);

    /**
     * Records a model attempt that was already admitted before a terminal fence.
     * Implementations may accept it until settlement even when its enclosing call
     * completes exactly on the deadline boundary.
     */
    default void recordAttemptUsage(AiUsageSnapshot usage) {
        recordUsage(usage);
    }

    /** Registers a live usage source and a fence that suppresses post-terminal writes. */
    void registerUsage(Supplier<AiUsageSnapshot> usage, Runnable lateWriteFence);

    /** Registers usage owned by an already admitted model attempt. */
    default void registerAttemptUsage(Supplier<AiUsageSnapshot> usage,
                                      Runnable lateWriteFence) {
        registerUsage(usage, lateWriteFence);
    }
}
