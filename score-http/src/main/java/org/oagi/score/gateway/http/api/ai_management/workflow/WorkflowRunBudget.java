package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
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
import java.util.function.LongSupplier;

/** One request-global call count, deadline, cancellation fence, and usage settlement. */
final class WorkflowRunBudget implements WorkflowRunControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRunBudget.class);
    private static final int MAXIMUM_CALLS = 128;
    private static final long CANCELLATION_POLL_NANOS = 100_000_000L;
    private static final long TERMINATION_GRACE_MILLIS = 250L;
    static final long USAGE_SETTLEMENT_GRACE_MILLIS = 1_000L;

    private final String requestId;
    private final AgentExecutionRecorder rootRecorder;
    private final Runnable cancellationFence;
    private final Runnable deadlineFence;
    private final long startedNanos;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private final InvocationStarter invocationStarter;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inFlightInvocations = new AtomicInteger();
    private final AtomicBoolean settlementStarted = new AtomicBoolean();
    private final AtomicBoolean settlementDeadlineScheduled = new AtomicBoolean();
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean deadlineSignalled = new AtomicBoolean();
    private final List<UsageSource> usage = new ArrayList<>();
    private boolean active = true;

    WorkflowRunBudget(String requestId, AgentExecutionRecorder rootRecorder,
                      Duration timeout, Runnable cancellationFence,
                      Runnable deadlineFence) {
        this(requestId, rootRecorder, timeout, cancellationFence,
                deadlineFence, System::nanoTime, Thread::startVirtualThread);
    }

    WorkflowRunBudget(String requestId, AgentExecutionRecorder rootRecorder,
                      Duration timeout, Runnable cancellationFence,
                      Runnable deadlineFence, LongSupplier nanoTime) {
        this(requestId, rootRecorder, timeout, cancellationFence,
                deadlineFence, nanoTime, Thread::startVirtualThread);
    }

    WorkflowRunBudget(String requestId, AgentExecutionRecorder rootRecorder,
                      Duration timeout, Runnable cancellationFence,
                      Runnable deadlineFence, LongSupplier nanoTime,
                      InvocationStarter invocationStarter) {
        this.requestId = requestId;
        this.rootRecorder = rootRecorder;
        this.cancellationFence = cancellationFence;
        this.deadlineFence = deadlineFence;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.invocationStarter = java.util.Objects.requireNonNull(
                invocationStarter, "invocationStarter");
        this.startedNanos = nanoTime.getAsLong();
        long resolvedTimeout;
        try {
            resolvedTimeout = timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            resolvedTimeout = Long.MAX_VALUE;
        }
        this.timeoutNanos = resolvedTimeout;
    }

    void charge(String operation) {
        checkpoint();
        if (calls.incrementAndGet() > MAXIMUM_CALLS) {
            throw new IllegalStateException(
                    "The Workflow execution budget was exhausted at " + operation + ".");
        }
    }

    AgentDecision invoke(AgentRunner runner,
                         org.oagi.score.gateway.http.api.ai_management.agent.Agent.AgentId id,
        AgentWorkflowContext context) {
        charge("Agent " + id.value());
        FutureTask<AgentDecision> call = new FutureTask<>(() -> {
            inFlightInvocations.incrementAndGet();
            try {
                return runner.run(id, context);
            } finally {
                invocationFinished();
            }
        });
        Thread worker;
        try {
            worker = invocationStarter.start(call);
        } catch (RuntimeException | Error startFailure) {
            throw startFailure;
        }
        try {
            while (true) {
                checkpoint();
                long remaining = remainingNanos();
                if (remaining <= 0) throw deadlineExceeded();
                try {
                    AgentDecision decision = call.get(
                            Math.min(remaining, CANCELLATION_POLL_NANOS),
                            TimeUnit.NANOSECONDS);
                    // AgentRunner publishes the attempt's usage before completing
                    // this Future. Recheck the absolute deadline only afterward so
                    // a boundary completion is accounted even when it is rejected.
                    if (remainingNanos() <= 0) throw deadlineExceeded();
                    return decision;
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
            throw new IllegalStateException("Agent runner execution failed.", cause);
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
            usage.add(new UsageSource(() -> snapshot, () -> { }, false));
        }
    }

    @Override
    public synchronized void recordAttemptUsage(AiUsageSnapshot snapshot) {
        // A model call admitted before the deadline can finish on the boundary.
        // Its billable usage belongs to this run even after admission is fenced,
        // provided the request-global settlement has not taken its snapshot yet.
        if (!settled.get() && snapshot != null) {
            usage.add(new UsageSource(() -> snapshot, () -> { }, true));
        }
    }

    @Override
    public void registerUsage(Supplier<AiUsageSnapshot> source,
                              Runnable lateWriteFence) {
        registerUsage(source, lateWriteFence, false);
    }

    @Override
    public void registerAttemptUsage(Supplier<AiUsageSnapshot> source,
                                     Runnable lateWriteFence) {
        registerUsage(source, lateWriteFence, true);
    }

    private void registerUsage(Supplier<AiUsageSnapshot> source,
                               Runnable lateWriteFence,
                               boolean admittedAttempt) {
        boolean fenceImmediately;
        synchronized (this) {
            fenceImmediately = admittedAttempt
                    ? settled.get() || !active && inFlightInvocations.get() == 0
                    : !active;
            if (!fenceImmediately) {
                usage.add(new UsageSource(source, lateWriteFence, admittedAttempt));
            }
        }
        if (fenceImmediately) lateWriteFence.run();
    }

    int usedCalls() {
        return calls.get();
    }

    void settleUsage() {
        if (!settlementStarted.compareAndSet(false, true)) return;
        terminate();
        finishUsageSettlement();
        scheduleForcedUsageSettlement();
    }

    private void finishUsageSettlement() {
        if (!settlementStarted.get() || inFlightInvocations.get() != 0) return;
        completeUsageSettlement();
    }

    private void scheduleForcedUsageSettlement() {
        if (settled.get() || inFlightInvocations.get() == 0
                || !settlementDeadlineScheduled.compareAndSet(false, true)) return;
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(USAGE_SETTLEMENT_GRACE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            completeUsageSettlement();
        });
    }

    private void completeUsageSettlement() {
        if (!settled.compareAndSet(false, true)) return;
        fenceDeferredUsage();
        List<UsageSource> sources;
        synchronized (this) {
            sources = List.copyOf(usage);
        }
        List<AiUsageSnapshot> snapshot = sources.stream()
                .map(UsageSource::snapshot)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (snapshot.isEmpty()) return;
        try {
            rootRecorder.recordSettledFanOutUsage(requestId + ":workflow",
                    "recursive_workflow", snapshot);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not settle recursive Workflow usage for request {}",
                    requestId, failure);
        }
    }

    private long remainingNanos() {
        // nanoTime readings may be negative or wrap. Their difference remains
        // valid for every representable execution timeout.
        long elapsed = nanoTime.getAsLong() - startedNanos;
        return timeoutNanos - elapsed;
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
            fences = usage.stream().filter(source -> !source.admittedAttempt())
                    .map(source -> (Runnable) source::fence).toList();
        }
        applyFences(fences);
    }

    private synchronized boolean isActive() {
        return active;
    }

    private void invocationFinished() {
        if (inFlightInvocations.decrementAndGet() == 0) {
            if (!isActive()) fenceDeferredUsage();
            finishUsageSettlement();
        }
    }

    private void fenceDeferredUsage() {
        List<Runnable> fences;
        synchronized (this) {
            fences = usage.stream().filter(UsageSource::admittedAttempt)
                    .map(source -> (Runnable) source::fence).toList();
        }
        applyFences(fences);
    }

    private void applyFences(List<Runnable> fences) {
        fences.forEach(fence -> {
            try {
                fence.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not apply a late-write fence for request {}",
                        requestId, failure);
            }
        });
    }

    private static final class UsageSource {
        private final Supplier<AiUsageSnapshot> snapshotSupplier;
        private final Runnable lateWriteFence;
        private final boolean admittedAttempt;
        private final AtomicBoolean fenced = new AtomicBoolean();

        private UsageSource(Supplier<AiUsageSnapshot> snapshot,
                            Runnable lateWriteFence,
                            boolean admittedAttempt) {
            this.snapshotSupplier = snapshot;
            this.lateWriteFence = lateWriteFence;
            this.admittedAttempt = admittedAttempt;
        }

        private AiUsageSnapshot snapshot() {
            return snapshotSupplier.get();
        }

        private boolean admittedAttempt() {
            return admittedAttempt;
        }

        private void fence() {
            if (fenced.compareAndSet(false, true)) lateWriteFence.run();
        }
    }

    static final class DeadlineExceededException extends IllegalStateException {
        private DeadlineExceededException() {
            super("The Workflow execution deadline was exceeded.");
        }
    }

    @FunctionalInterface
    interface InvocationStarter {
        Thread start(Runnable invocation);
    }
}
