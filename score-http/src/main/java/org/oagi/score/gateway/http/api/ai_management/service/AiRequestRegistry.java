package org.oagi.score.gateway.http.api.ai_management.service;

import jakarta.annotation.PreDestroy;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiPublicExecutionRequestStatus;
import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.api.ai_management.model.AiRequestStopSignal;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationCommitFence;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
@DependsOn("scoreAiChatExecutor")
public class AiRequestRegistry implements ConversationCommitFence {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRequestRegistry.class);
    private static final Duration TERMINAL_RETENTION = Duration.ofMinutes(30);
    private static final Duration DEFAULT_STOP_GRACE_PERIOD = Duration.ofSeconds(30);

    private final Map<String, Entry> localRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Duration stopGracePeriod;
    private final AiRequestStateStore stateStore;
    private final String instanceId;
    private final AiRequestSharedStatePolicy sharedStatePolicy;
    private final AiRequestAdmissionCoordinator admission;
    private final AiRequestControl control;
    private final AiRequestTimeoutCoordinator timeouts;
    private final Runnable beforeInactivityTimeoutTransition;
    private final Runnable afterChangeCountDecrement;

    @Autowired
    public AiRequestRegistry(@Qualifier("scoreAiLifecycleScheduler") ScheduledExecutorService scheduler,
                             AiRequestStateStore stateStore) {
        this(scheduler, DEFAULT_STOP_GRACE_PERIOD, stateStore);
    }

    AiRequestRegistry(ScheduledExecutorService scheduler, Duration stopGracePeriod) {
        this(scheduler, stopGracePeriod, AiRequestStateStore.inMemory());
    }

    AiRequestRegistry(ScheduledExecutorService scheduler, Duration stopGracePeriod,
                      AiRequestStateStore stateStore) {
        this(scheduler, stopGracePeriod, stateStore, () -> { });
    }

    AiRequestRegistry(ScheduledExecutorService scheduler, Duration stopGracePeriod,
                      AiRequestStateStore stateStore,
                      Runnable beforeInactivityTimeoutTransition) {
        this(scheduler, stopGracePeriod, stateStore,
                beforeInactivityTimeoutTransition, () -> { });
    }

    AiRequestRegistry(ScheduledExecutorService scheduler, Duration stopGracePeriod,
                      AiRequestStateStore stateStore,
                      Runnable beforeInactivityTimeoutTransition,
                      Runnable afterChangeCountDecrement) {
        this.scheduler = scheduler;
        this.stopGracePeriod = stopGracePeriod;
        this.stateStore = stateStore;
        this.beforeInactivityTimeoutTransition = beforeInactivityTimeoutTransition;
        this.afterChangeCountDecrement = afterChangeCountDecrement;
        this.instanceId = UUID.randomUUID().toString();
        this.sharedStatePolicy = new AiRequestSharedStatePolicy(
                stateStore, instanceId, stopGracePeriod);
        this.admission = new AiRequestAdmissionCoordinator(stateStore, sharedStatePolicy,
                instanceId, stopGracePeriod, TERMINAL_RETENTION);
        this.control = new AiRequestControl(
                stateStore, sharedStatePolicy, instanceId, this::progress);
        this.timeouts = new AiRequestTimeoutCoordinator(localRequests, scheduler,
                stopGracePeriod, TERMINAL_RETENTION, stateStore, sharedStatePolicy,
                beforeInactivityTimeoutTransition, this::applyStopState,
                this::markLocalTerminal, this::shutdownLocalEntry);
        this.stateStore.addStopListener(this::stopRequested);
    }

    public AiRequestRegistry() {
        this(java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("score-ai-registry-test-", 0).daemon(true).factory()),
                DEFAULT_STOP_GRACE_PERIOD, AiRequestStateStore.inMemory());
    }

    @PreDestroy
    void terminalizeOwnedRequestsOnShutdown() {
        timeouts.terminalizeOwnedRequests();
    }

    private void shutdownLocalEntry(Entry entry) {
        interruptLocalWorker(entry);
        clearLocalExecution(entry);
        localRequests.remove(entry.requestId, entry);
    }

    public Entry register(String requestId, String conversationId, ScoreUser requester, Instant deadline) {
        AiSharedRequestState state = admission.reserve(
                requestId, conversationId, requester, deadline);
        Entry entry = new Entry(requestId, state.generation(),
                new AiRequestInactivityLease(Duration.between(state.createdAt(), deadline),
                        System.nanoTime()));
        if (localRequests.putIfAbsent(requestId, entry) != null) {
            sharedStatePolicy.rollbackRegistration(state);
            throw new IllegalArgumentException("An AI request with this requestId already exists.");
        }
        try {
            timeouts.scheduleInitial(entry);
        } catch (RejectedExecutionException exception) {
            localRequests.remove(requestId, entry);
            sharedStatePolicy.rollbackRegistration(state);
            throw exception;
        }
        return entry;
    }

    public void bindConversation(Entry entry, String conversationId) {
        admission.bind(entry, conversationId);
        progress(entry.requestId);
    }

    /** Prevents model changes or deletion from racing request admission on any instance. */
    public <T> T whileConversationIdle(String conversationId, Supplier<T> action) {
        return admission.whileConversationIdle(conversationId, action);
    }

    /**
     * Fences a confirmation decision against both its issuing request and the
     * path conversation. This covers child-agent confirmations whose active
     * lifecycle is registered against a different root conversation.
     */
    public <T> T whileRequestAndConversationIdle(
            String requestId, String conversationId, Supplier<T> action) {
        return admission.whileRequestAndConversationIdle(
                requestId, conversationId, action);
    }

    public boolean start(Entry entry) {
        synchronized (entry) {
            if (entry.workerThread != null || entry.locallyTerminal) {
                return false;
            }
            entry.workerThread = Thread.currentThread();
            boolean started = stateStore.withRequestLock(entry.requestId, storage -> {
                AiSharedRequestState state = storage.get(entry.requestId);
                if (!sharedStatePolicy.matchesOwner(state, entry)
                        || !"REGISTERED".equals(state.status())) {
                    return false;
                }
                storage.put(state.started(Instant.now()));
                return true;
            });
            if (!started) {
                entry.workerThread = null;
            } else {
                entry.activityEpoch.incrementAndGet();
                entry.inactivityLease.progress(System.nanoTime());
            }
            return started;
        }
    }

    public void complete(Entry entry) { finish(entry, null); }

    public void fail(Entry entry, Throwable throwable) { finish(entry, throwable); }

    public String finish(Entry entry, Throwable throwable) {
        AiSharedRequestState terminal = stateStore.withRequestLock(entry.requestId, storage -> {
            AiSharedRequestState state = storage.get(entry.requestId);
            if (!sharedStatePolicy.matchesOwner(state, entry)) {
                return state;
            }
            if (state.terminal()) {
                return state;
            }
            Instant now = Instant.now();
            AiSharedRequestState finished;
            if ("CANCELLING".equals(state.status())) {
                String target = state.changeOutcomeUncertain()
                        ? "UNKNOWN_RECONCILIATION_REQUIRED" : state.terminalTarget();
                String reason = state.changeOutcomeUncertain()
                        ? "CHANGE_OUTCOME_UNCERTAIN" : state.statusReason();
                finished = state.terminal(target, reason, now);
            } else if (throwable == null) {
                finished = state.terminal("COMPLETED", null, now);
            } else {
                finished = state.terminal("FAILED", throwable.getClass().getSimpleName(), now);
            }
            storage.put(finished);
            return finished;
        });
        if (terminal == null) {
            markLocalTerminal(entry);
            return "FAILED";
        }
        if (terminal.terminal()) {
            markLocalTerminal(entry);
        }
        return terminal.status();
    }

    /** Records observable work without extending the lease merely because it is being polled. */
    public void progress(String requestId) {
        timeouts.progress(requestId);
    }

    void dispatch(Entry entry, String operation, Runnable task) {
        timeouts.dispatch(entry, operation, task);
    }

    public boolean changeStarted(String requestId) {
        Entry entry = localRequests.get(requestId);
        if (entry == null) return false;
        boolean started = stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            if (!sharedStatePolicy.matchesOwner(state, entry)
                    || !"RUNNING".equals(state.status())) {
                return false;
            }
            storage.put(state.changeStarted(Instant.now()));
            // This atomic epoch is updated while holding the same distributed state
            // lock used by timeout transition, so even a fast change cannot occur
            // invisibly between inactivity review and terminalization.
            entry.activityEpoch.incrementAndGet();
            return true;
        });
        if (started) progress(requestId);
        return started;
    }

    /**
     * Protects a separately bounded user interaction from the request inactivity lease.
     * The interaction's own timeout remains authoritative until the wait finishes.
     */
    public boolean interactionStarted(String requestId) {
        Entry entry = localRequests.get(requestId);
        if (entry == null) return false;
        synchronized (entry) {
            if (entry.locallyTerminal || entry.inactivityLease.expired()) return false;
            entry.interactionsInFlight++;
            entry.activityEpoch.incrementAndGet();
            entry.inactivityLease.progress(System.nanoTime());
            return true;
        }
    }

    /** Starts a fresh inactivity window after a protected user interaction finishes. */
    public void interactionFinished(String requestId) {
        Entry entry = localRequests.get(requestId);
        if (entry == null) return;
        synchronized (entry) {
            if (entry.interactionsInFlight > 0) {
                entry.interactionsInFlight--;
            }
            if (!entry.locallyTerminal) {
                entry.activityEpoch.incrementAndGet();
                entry.inactivityLease.progress(System.nanoTime());
            }
        }
    }

    public boolean isTimingOut(String requestId) {
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            return state != null && ("TIMED_OUT".equals(state.status())
                    || "TIMED_OUT".equals(state.terminalTarget()));
        });
    }

    public boolean isCancelling(String requestId) {
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            return state != null && ("CANCELLING".equals(state.status())
                    || "CANCELLED".equals(state.status())
                    || "CANCELLED".equals(state.terminalTarget()));
        });
    }

    public void changeFinished(String requestId) {
        // Publish completion activity before removing the definite-work fence. Otherwise an
        // inactivity review could observe changeInFlight=0 while the old lease is expired.
        progress(requestId);
        Entry entry = localRequests.get(requestId);
        boolean decremented = stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            if (entry != null && sharedStatePolicy.matchesOwner(state, entry)) {
                storage.put(state.changeFinished(Instant.now()));
                entry.activityEpoch.incrementAndGet();
                return true;
            }
            return false;
        });
        if (decremented) afterChangeCountDecrement.run();
        progress(requestId);
    }

    public boolean hasActiveConversation(String conversationId) {
        return admission.hasActiveConversation(conversationId);
    }

    public boolean shouldDiscardResult(String requestId) {
        return control.shouldDiscardResult(requestId);
    }

    /**
     * Linearizes Tool admission with cancellation/timeout state changes.
     * The Tool may run after this method returns because its execution was
     * admitted before a later stop signal; a stop that wins the lock rejects it.
     */
    public void admitToolExecution(String requestId) {
        control.admitToolExecution(requestId);
    }

    /** Applies the registry's normal timeout fence when a nested execution budget expires. */
    public void timeoutExecution(String requestId) {
        Entry entry = localRequests.get(requestId);
        if (entry != null) timeouts.timeout(entry, false);
    }

    /** Atomically fences cluster-wide cancellation/inactivity against final persistence. */
    public boolean commitResult(String requestId, Runnable persistence) {
        Entry entry = localRequests.get(requestId);
        if (entry == null) {
            return false;
        }
        boolean committed;
        synchronized (entry) {
            committed = stateStore.withRequestLock(requestId, storage -> {
                AiSharedRequestState state = storage.get(requestId);
                if (!sharedStatePolicy.matchesOwner(state, entry)
                        || !"RUNNING".equals(state.status())) {
                    return false;
                }
                persistence.run();
                storage.put(state.terminal("COMPLETED", null, Instant.now()));
                return true;
            });
        }
        if (committed) {
            markLocalTerminal(entry);
        }
        return committed;
    }

    public AiCancellationResponse cancel(String requestId, String cancellationRequestId,
                                         String conversationId, Long expectedGeneration,
                                         ScoreUser requester) {
        return control.cancel(requestId, cancellationRequestId,
                conversationId, expectedGeneration, requester);
    }

    public AiCancellationResponse cancel(String requestId, String cancellationRequestId,
                                         ScoreUser requester) {
        return cancel(requestId, cancellationRequestId, null, null, requester);
    }

    public AiPublicExecutionRequestStatus status(String requestId, ScoreUser requester) {
        return control.status(requestId, requester);
    }

    public Optional<AiPublicExecutionRequestStatus> active(ScoreUser requester) {
        return control.active(requester);
    }

    public String cancellationRequestId(Entry entry) {
        return sharedState(entry).map(AiSharedRequestState::cancellationRequestId).orElse(null);
    }

    private void stopRequested(AiRequestStopSignal signal) {
        Entry entry = localRequests.get(signal.requestId());
        if (entry == null || entry.generation != signal.generation()) {
            return;
        }
        AiSharedRequestState state = sharedState(entry).orElse(null);
        applyStopState(entry, state);
    }

    private void applyStopState(Entry entry, AiSharedRequestState state) {
        applyStopState(entry, state, true);
    }

    private void applyStopState(Entry entry, AiSharedRequestState state,
                                boolean interruptCurrentWorker) {
        if (state == null || state.generation() != entry.generation) {
            return;
        }
        Thread worker = null;
        boolean watchdog = false;
        synchronized (entry) {
            if ("CANCELLING".equals(state.status())) {
                worker = entry.workerThread;
                watchdog = worker != null;
            } else if (state.terminal() && !"COMPLETED".equals(state.status())) {
                worker = entry.workerThread;
            }
        }
        if (worker != null
                && (interruptCurrentWorker || worker != Thread.currentThread())) {
            worker.interrupt();
        }
        if (watchdog) {
            timeouts.scheduleStopWatchdog(entry);
        }
        if (state.terminal()) {
            markLocalTerminal(entry);
        }
    }

    private void markLocalTerminal(Entry entry) {
        if (!clearLocalExecution(entry)) {
            return;
        }
        try {
            scheduler.schedule(() -> localRequests.remove(entry.requestId, entry),
                    TERMINAL_RETENTION.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            localRequests.remove(entry.requestId, entry);
            LOGGER.debug("AI lifecycle scheduler stopped before local cleanup for request {}",
                    entry.requestId, exception);
        }
    }

    private boolean clearLocalExecution(Entry entry) {
        synchronized (entry) {
            entry.workerThread = null;
            if (entry.leaseReviewTask != null) {
                entry.leaseReviewTask.cancel(false);
                entry.leaseReviewTask = null;
            }
            if (entry.stopWatchdogTask != null) {
                entry.stopWatchdogTask.cancel(false);
                entry.stopWatchdogTask = null;
            }
            if (entry.locallyTerminal) {
                return false;
            }
            entry.locallyTerminal = true;
            return true;
        }
    }

    private void interruptLocalWorker(Entry entry) {
        Thread worker;
        synchronized (entry) {
            worker = entry.workerThread;
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
    }

    private Optional<AiSharedRequestState> sharedState(Entry entry) {
        return Optional.ofNullable(stateStore.withRequestLock(
                entry.requestId, storage -> storage.get(entry.requestId)));
    }

    public static final class Entry {
        final String requestId;
        final long generation;
        final AiRequestInactivityLease inactivityLease;
        final AtomicLong activityEpoch = new AtomicLong();
        Thread workerThread;
        ScheduledFuture<?> leaseReviewTask;
        ScheduledFuture<?> stopWatchdogTask;
        int interactionsInFlight;
        boolean locallyTerminal;

        private Entry(String requestId, long generation,
                      AiRequestInactivityLease inactivityLease) {
            this.requestId = requestId;
            this.generation = generation;
            this.inactivityLease = inactivityLease;
        }

        public String requestId() { return requestId; }
        public long generation() { return generation; }
        synchronized boolean hasWorkerThread() { return workerThread != null; }
        synchronized boolean hasLeaseReviewTask() { return leaseReviewTask != null; }
    }
}
