package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Independent observable-progress lease and late-write fence for one Agent invocation. */
final class AgentInvocationLease implements WorkflowRunControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentInvocationLease.class);

    private final String agentId;
    private final long inactivityTimeoutNanos;
    private final LongSupplier nanoTime;
    private final WorkflowRunControl requestControl;
    private final AtomicLong lastProgressNanos;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger definiteActivities = new AtomicInteger();
    private final Object activityMonitor = new Object();
    private final List<InvocationFence> fences = new ArrayList<>();

    AgentInvocationLease(String agentId, long inactivityTimeoutNanos,
                         LongSupplier nanoTime, WorkflowRunControl requestControl) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.inactivityTimeoutNanos = inactivityTimeoutNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.requestControl = Objects.requireNonNull(requestControl, "requestControl");
        this.lastProgressNanos = new AtomicLong(nanoTime.getAsLong());
    }

    long remainingNanos() {
        synchronized (activityMonitor) {
            if (stopped.get()) return 0L;
            long now = nanoTime.getAsLong();
            if (definiteActivities.get() > 0) {
                lastProgressNanos.set(now);
                return inactivityTimeoutNanos;
            }
            return inactivityTimeoutNanos - (now - lastProgressNanos.get());
        }
    }

    @Override
    public void checkpoint() {
        requestControl.checkpoint();
        verifyActive();
    }

    @Override
    public void progress() {
        requestControl.progress();
        synchronized (activityMonitor) {
            if (stopped.get()) throw new AgentInvocationStalledException(agentId);
            lastProgressNanos.set(nanoTime.getAsLong());
        }
    }

    @Override
    public void definiteActivityStarted() {
        requestControl.definiteActivityStarted();
        synchronized (activityMonitor) {
            if (stopped.get()) {
                requestControl.definiteActivityFinished();
                throw new AgentInvocationStalledException(agentId);
            }
            long now = nanoTime.getAsLong();
            definiteActivities.incrementAndGet();
            lastProgressNanos.set(now);
        }
    }

    @Override
    public void definiteActivityFinished() {
        synchronized (activityMonitor) {
            definiteActivities.updateAndGet(current -> Math.max(0, current - 1));
            if (!stopped.get()) {
                lastProgressNanos.set(nanoTime.getAsLong());
            }
        }
        requestControl.definiteActivityFinished();
    }

    @Override
    public void recordUsage(AiUsageSnapshot usage) {
        synchronized (fences) {
            if (!stopped.get()) requestControl.recordUsage(usage);
        }
    }

    @Override
    public void recordAttemptUsage(AiUsageSnapshot usage) {
        requestControl.recordAttemptUsage(usage);
    }

    @Override
    public void registerUsage(Supplier<AiUsageSnapshot> usage, Runnable lateWriteFence) {
        requestControl.registerUsage(usage, registerFence(lateWriteFence, false));
    }

    @Override
    public void registerAttemptUsage(Supplier<AiUsageSnapshot> usage,
                                     Runnable lateWriteFence) {
        requestControl.registerAttemptUsage(usage, registerFence(lateWriteFence, true));
    }

    void verifyActive() {
        if (remainingNanos() <= 0) {
            AgentInvocationStalledException stalled = stallIfInactive();
            if (stalled != null) throw stalled;
        }
    }

    AgentInvocationStalledException stallIfInactive() {
        synchronized (activityMonitor) {
            if (definiteActivities.get() > 0) {
                lastProgressNanos.set(nanoTime.getAsLong());
                return null;
            }
            long now = nanoTime.getAsLong();
            if (inactivityTimeoutNanos - (now - lastProgressNanos.get()) > 0) {
                return null;
            }
            stopWhileHoldingActivityMonitor();
            return new AgentInvocationStalledException(agentId);
        }
    }

    void stop() {
        synchronized (activityMonitor) {
            stopWhileHoldingActivityMonitor();
        }
    }

    private void stopWhileHoldingActivityMonitor() {
        boolean includeAdmittedAttempts;
        synchronized (fences) {
            if (!stopped.compareAndSet(false, true)) return;
            includeAdmittedAttempts = finished.get();
        }
        fenceRegistered(includeAdmittedAttempts);
    }

    void invocationFinished() {
        boolean fence;
        synchronized (fences) {
            finished.set(true);
            fence = stopped.get();
        }
        if (fence) fenceRegistered(true);
    }

    private Runnable registerFence(Runnable fence, boolean admittedAttempt) {
        InvocationFence registered = new InvocationFence(fence, admittedAttempt);
        boolean fenceImmediately;
        synchronized (fences) {
            fences.add(registered);
            fenceImmediately = stopped.get() && (!admittedAttempt || finished.get());
        }
        if (fenceImmediately) registered.run();
        return registered;
    }

    private void fenceRegistered(boolean includeAdmittedAttempts) {
        List<InvocationFence> snapshot;
        synchronized (fences) {
            snapshot = List.copyOf(fences);
        }
        snapshot.stream()
                .filter(fence -> includeAdmittedAttempts || !fence.admittedAttempt())
                .forEach(fence -> {
                    try {
                        fence.run();
                    } catch (RuntimeException failure) {
                        LOGGER.warn("Could not apply an invocation late-write fence for Agent {}",
                                agentId, failure);
                    }
                });
    }

    private static final class InvocationFence implements Runnable {
        private final Runnable delegate;
        private final boolean admittedAttempt;
        private final AtomicBoolean applied = new AtomicBoolean();

        private InvocationFence(Runnable delegate, boolean admittedAttempt) {
            this.delegate = Objects.requireNonNull(delegate, "lateWriteFence");
            this.admittedAttempt = admittedAttempt;
        }

        private boolean admittedAttempt() {
            return admittedAttempt;
        }

        @Override
        public void run() {
            if (applied.compareAndSet(false, true)) delegate.run();
        }
    }
}
