package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Coordinates inactivity leases, timeout transitions, and stop watchdogs. */
final class AiRequestTimeoutCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiRequestTimeoutCoordinator.class);

    private final Map<String, AiRequestRegistry.Entry> localRequests;
    private final ScheduledExecutorService scheduler;
    private final Duration stopGracePeriod;
    private final Duration terminalRetention;
    private final AiRequestStateStore stateStore;
    private final AiRequestSharedStatePolicy policy;
    private final Runnable beforeTimeoutTransition;
    private final StopStateHandler stopStateHandler;
    private final Consumer<AiRequestRegistry.Entry> terminalMarker;
    private final Consumer<AiRequestRegistry.Entry> shutdownCleanup;

    AiRequestTimeoutCoordinator(
            Map<String, AiRequestRegistry.Entry> localRequests,
            ScheduledExecutorService scheduler, Duration stopGracePeriod,
            Duration terminalRetention, AiRequestStateStore stateStore,
            AiRequestSharedStatePolicy policy, Runnable beforeTimeoutTransition,
            StopStateHandler stopStateHandler,
            Consumer<AiRequestRegistry.Entry> terminalMarker,
            Consumer<AiRequestRegistry.Entry> shutdownCleanup) {
        this.localRequests = localRequests;
        this.scheduler = scheduler;
        this.stopGracePeriod = stopGracePeriod;
        this.terminalRetention = terminalRetention;
        this.stateStore = stateStore;
        this.policy = policy;
        this.beforeTimeoutTransition = beforeTimeoutTransition;
        this.stopStateHandler = stopStateHandler;
        this.terminalMarker = terminalMarker;
        this.shutdownCleanup = shutdownCleanup;
    }

    void terminalizeOwnedRequests() {
        Instant now = Instant.now();
        for (AiRequestRegistry.Entry entry : List.copyOf(localRequests.values())) {
            try {
                stateStore.withRequestLock(entry.requestId(), storage -> {
                    AiSharedRequestState state = storage.get(entry.requestId());
                    if (!policy.matchesOwner(state, entry) || state.terminal()) return null;
                    String status = state.changeObserved()
                            ? "UNKNOWN_RECONCILIATION_REQUIRED" : "FAILED";
                    storage.put(state.terminal(status, "WORKER_INSTANCE_SHUTDOWN", now));
                    return null;
                });
            } catch (RuntimeException exception) {
                LOGGER.error("Could not terminalize AI request {} while this instance"
                        + " was shutting down", entry.requestId(), exception);
            } finally {
                shutdownCleanup.accept(entry);
            }
        }
    }

    void scheduleInitial(AiRequestRegistry.Entry entry) {
        scheduleLeaseReview(entry, entry.inactivityLease.firstReviewNanos());
    }

    void progress(String requestId) {
        AiRequestRegistry.Entry entry = localRequests.get(requestId);
        if (entry == null) return;
        synchronized (entry) {
            if (!entry.locallyTerminal) {
                entry.activityEpoch.incrementAndGet();
                entry.inactivityLease.progress(System.nanoTime());
            }
        }
    }

    void timeout(AiRequestRegistry.Entry entry, boolean interruptCurrentWorker) {
        requestTimeout(entry, interruptCurrentWorker, null);
    }

    void scheduleStopWatchdog(AiRequestRegistry.Entry entry) {
        synchronized (entry) {
            if (entry.stopWatchdogTask != null) entry.stopWatchdogTask.cancel(false);
            try {
                entry.stopWatchdogTask = scheduler.schedule(
                        () -> dispatch(entry, "stop watchdog", () -> stopWatchdog(entry)),
                        stopGracePeriod.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException exception) {
                LOGGER.error("Could not schedule the AI stop watchdog for request {};"
                        + " reconciling immediately", entry.requestId(), exception);
                dispatch(entry, "stop watchdog fallback", () -> stopWatchdog(entry));
            }
        }
    }

    void dispatch(AiRequestRegistry.Entry entry, String operation, Runnable task) {
        try {
            Thread.startVirtualThread(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    LOGGER.error("AI request {} lifecycle {} task failed",
                            entry.requestId(), operation, failure);
                    lifecycleTaskFailed(entry, operation);
                }
            });
        } catch (RuntimeException | Error failure) {
            LOGGER.error("Could not dispatch AI request {} lifecycle {} task",
                    entry.requestId(), operation, failure);
            lifecycleTaskFailed(entry, operation);
        }
    }

    private void reviewInactivity(AiRequestRegistry.Entry entry) {
        long remainingNanos;
        synchronized (entry) {
            if (entry.locallyTerminal || entry.inactivityLease.expired()) return;
            entry.leaseReviewTask = null;
            remainingNanos = entry.inactivityLease.remainingNanos(System.nanoTime());
        }
        boolean definiteWorkInFlight = remainingNanos <= 0
                && (interactionInFlight(entry) || changeInFlight(entry));
        long reviewedActivityEpoch;
        AiRequestInactivityLease.Review review;
        synchronized (entry) {
            reviewedActivityEpoch = entry.activityEpoch.get();
            review = entry.inactivityLease.review(
                    System.nanoTime(), definiteWorkInFlight);
        }
        if (review.expired()) {
            beforeTimeoutTransition.run();
            requestTimeout(entry, true, reviewedActivityEpoch);
            return;
        }
        if (!publishLease(entry, review.remainingNanos())) return;
        try {
            scheduleLeaseReview(entry,
                    entry.inactivityLease.nextReviewNanos(review.remainingNanos()));
        } catch (RejectedExecutionException exception) {
            LOGGER.error("Could not schedule the inactivity review for AI request {}",
                    entry.requestId(), exception);
            lifecycleTaskFailed(entry, "inactivity review scheduling");
        }
    }

    private boolean changeInFlight(AiRequestRegistry.Entry entry) {
        return stateStore.withRequestLock(entry.requestId(), storage -> {
            AiSharedRequestState state = storage.get(entry.requestId());
            return policy.matchesOwner(state, entry) && !state.terminal()
                    && !"CANCELLING".equals(state.status())
                    && state.changeInFlight() > 0;
        });
    }

    private boolean interactionInFlight(AiRequestRegistry.Entry entry) {
        synchronized (entry) {
            return !entry.locallyTerminal && entry.interactionsInFlight > 0;
        }
    }

    private boolean publishLease(AiRequestRegistry.Entry entry, long remainingNanos) {
        Instant now = Instant.now();
        Instant deadline = now.plusMillis(Math.max(
                1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        Instant expiresAt = deadline.plus(stopGracePeriod).plus(terminalRetention);
        return stateStore.withRequestLock(entry.requestId(), storage -> {
            AiSharedRequestState state = storage.get(entry.requestId());
            if (!policy.matchesOwner(state, entry) || state.terminal()
                    || "CANCELLING".equals(state.status())) return false;
            storage.put(state.leaseRenewed(deadline, expiresAt, now));
            return true;
        });
    }

    private void scheduleLeaseReview(AiRequestRegistry.Entry entry, long delayNanos) {
        ScheduledFuture<?> review = scheduler.schedule(
                () -> dispatch(entry, "inactivity review", () -> reviewInactivity(entry)),
                Math.max(1L, delayNanos), TimeUnit.NANOSECONDS);
        synchronized (entry) {
            if (entry.locallyTerminal || entry.inactivityLease.expired()) {
                review.cancel(false);
            } else {
                entry.leaseReviewTask = review;
            }
        }
    }

    private void requestTimeout(AiRequestRegistry.Entry entry,
                                boolean interruptCurrentWorker,
                                Long reviewedActivityEpoch) {
        TimeoutTransition transition;
        synchronized (entry) {
            boolean workerPresent = entry.workerThread != null;
            transition = stateStore.withRequestLock(entry.requestId(), storage -> {
                AiSharedRequestState current = storage.get(entry.requestId());
                if (!policy.matchesOwner(current, entry) || current.terminal()
                        || "CANCELLING".equals(current.status())) {
                    return new TimeoutTransition(null, false);
                }
                boolean activitySinceReview = reviewedActivityEpoch != null
                        && entry.activityEpoch.get() != reviewedActivityEpoch;
                if (activitySinceReview || current.changeInFlight() > 0) {
                    return new TimeoutTransition(current, true);
                }
                AiSharedRequestState timed = current.timingOut(
                        Instant.now(), workerPresent);
                storage.put(timed);
                return new TimeoutTransition(timed, false);
            });
        }
        if (transition.definiteWorkInFlight()) {
            renewAfterDefiniteWork(entry);
        } else {
            stopStateHandler.apply(
                    entry, transition.state(), interruptCurrentWorker);
        }
    }

    private void renewAfterDefiniteWork(AiRequestRegistry.Entry entry) {
        long nowNanos = System.nanoTime();
        synchronized (entry) {
            if (entry.locallyTerminal) return;
            entry.inactivityLease.renew(nowNanos);
        }
        long remainingNanos = entry.inactivityLease.remainingNanos(nowNanos);
        if (!publishLease(entry, remainingNanos)) return;
        try {
            scheduleLeaseReview(entry,
                    entry.inactivityLease.nextReviewNanos(remainingNanos));
        } catch (RejectedExecutionException exception) {
            LOGGER.error("Could not reschedule the inactivity review for AI request {}",
                    entry.requestId(), exception);
            lifecycleTaskFailed(entry, "inactivity review rescheduling");
        }
    }

    private void stopWatchdog(AiRequestRegistry.Entry entry) {
        Thread worker;
        synchronized (entry) {
            worker = entry.workerThread;
            entry.workerThread = null;
        }
        if (worker == null) return;
        worker.interrupt();
        AiSharedRequestState state = stateStore.withRequestLock(entry.requestId(), storage -> {
            AiSharedRequestState current = storage.get(entry.requestId());
            if (!policy.matchesOwner(current, entry)
                    || !"CANCELLING".equals(current.status())) return current;
            AiSharedRequestState reconciled = current.terminal(
                    "UNKNOWN_RECONCILIATION_REQUIRED", "WORKER_STOP_TIMEOUT", Instant.now());
            storage.put(reconciled);
            return reconciled;
        });
        if (state != null && state.terminal()) terminalMarker.accept(entry);
    }

    private void lifecycleTaskFailed(AiRequestRegistry.Entry entry, String operation) {
        Thread worker;
        synchronized (entry) {
            worker = entry.workerThread;
            entry.workerThread = null;
        }
        if (worker != null) worker.interrupt();
        AiSharedRequestState state = stateStore.withRequestLock(entry.requestId(), storage -> {
            AiSharedRequestState current = storage.get(entry.requestId());
            if (!policy.matchesOwner(current, entry) || current.terminal()) return current;
            String reason = operation.toUpperCase(Locale.ROOT).replace(' ', '_') + "_FAILED";
            AiSharedRequestState failed = current.terminal(
                    "UNKNOWN_RECONCILIATION_REQUIRED", reason, Instant.now());
            storage.put(failed);
            return failed;
        });
        if (state != null && state.terminal()) terminalMarker.accept(entry);
    }

    private record TimeoutTransition(AiSharedRequestState state,
                                     boolean definiteWorkInFlight) { }

    @FunctionalInterface
    interface StopStateHandler {
        void apply(AiRequestRegistry.Entry entry, AiSharedRequestState state,
                   boolean interruptCurrentWorker);
    }
}
