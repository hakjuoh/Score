package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** One request-global call count, deadline, cancellation fence, and usage settlement. */
final class WorkflowRunBudget implements WorkflowRunControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRunBudget.class);
    private static final int MAXIMUM_CALLS = 128;
    private static final long CANCELLATION_POLL_NANOS = 100_000_000L;
    private static final long TERMINATION_GRACE_MILLIS = 250L;

    private final String requestId;
    private final AiTrajectoryRecorder rootRecorder;
    private final Runnable cancellationFence;
    private final Runnable deadlineFence;
    private final long deadlineNanos;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean deadlineSignalled = new AtomicBoolean();
    private final List<UsageSource> usage = new ArrayList<>();
    private boolean active = true;

    WorkflowRunBudget(String requestId, AiTrajectoryRecorder rootRecorder,
                      Duration timeout, Runnable cancellationFence,
                      Runnable deadlineFence) {
        this.requestId = requestId;
        this.rootRecorder = rootRecorder;
        this.cancellationFence = cancellationFence;
        this.deadlineFence = deadlineFence;
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long now = System.nanoTime();
        this.deadlineNanos = timeoutNanos >= Long.MAX_VALUE - now
                ? Long.MAX_VALUE : now + timeoutNanos;
    }

    void charge(String operation) {
        checkpoint();
        if (calls.incrementAndGet() > MAXIMUM_CALLS) {
            throw new IllegalStateException(
                    "The Workflow execution budget was exhausted at " + operation + ".");
        }
    }

    AgentDecision invoke(WorkflowAgent agent, AgentWorkflowContext context) {
        charge("Agent " + agent.callId().value());
        FutureTask<AgentDecision> call = new FutureTask<>(() -> agent.execute(context));
        Thread worker = Thread.startVirtualThread(call);
        try {
            while (true) {
                checkpoint();
                long remaining = remainingNanos();
                try {
                    return call.get(Math.min(remaining, CANCELLATION_POLL_NANOS),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException pollOrDeadline) {
                    if (remainingNanos() <= 0) throw deadlineExceeded();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Workflow execution was interrupted.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Workflow Agent execution failed.", cause);
        } finally {
            if (!call.isDone()) {
                call.cancel(true);
                worker.interrupt();
                try {
                    worker.join(TERMINATION_GRACE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public void checkpoint() {
        try {
            cancellationFence.run();
        } catch (RuntimeException terminal) {
            terminate();
            throw terminal;
        }
        if (remainingNanos() <= 0) throw deadlineExceeded();
    }

    @Override
    public synchronized void recordUsage(AiUsageSnapshot snapshot) {
        if (active && snapshot != null) {
            usage.add(new UsageSource(() -> snapshot, () -> { }));
        }
    }

    @Override
    public void registerUsage(Supplier<AiUsageSnapshot> source,
                              Runnable lateWriteFence) {
        boolean fenceImmediately;
        synchronized (this) {
            fenceImmediately = !active;
            if (!fenceImmediately) {
                usage.add(new UsageSource(source, lateWriteFence));
            }
        }
        if (fenceImmediately) lateWriteFence.run();
    }

    int usedCalls() {
        return calls.get();
    }

    void settleUsage() {
        if (!settled.compareAndSet(false, true)) return;
        terminate();
        List<UsageSource> sources;
        synchronized (this) {
            sources = List.copyOf(usage);
        }
        List<AiUsageSnapshot> snapshot = sources.stream()
                .map(UsageSource::snapshot)
                .map(Supplier::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (snapshot.isEmpty()) return;
        try {
            rootRecorder.recordFanOutUsage(requestId + ":workflow",
                    "recursive_workflow", snapshot);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not settle recursive Workflow usage for request {}",
                    requestId, failure);
        }
    }

    private long remainingNanos() {
        return deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
    }

    private DeadlineExceededException deadlineExceeded() {
        terminate();
        if (deadlineSignalled.compareAndSet(false, true)) deadlineFence.run();
        return new DeadlineExceededException();
    }

    private void terminate() {
        List<Runnable> fences;
        synchronized (this) {
            if (!active) return;
            active = false;
            fences = usage.stream().map(UsageSource::lateWriteFence).toList();
        }
        fences.forEach(fence -> {
            try {
                fence.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not apply a late-write fence for request {}",
                        requestId, failure);
            }
        });
    }

    private record UsageSource(Supplier<AiUsageSnapshot> snapshot,
                               Runnable lateWriteFence) { }

    private static final class DeadlineExceededException extends IllegalStateException {
        private DeadlineExceededException() {
            super("The Workflow execution deadline was exceeded.");
        }
    }
}
