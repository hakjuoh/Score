package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiPublicExecutionRequestStatus;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Multi-instance request lifecycle registry.
 *
 * Redis is authoritative for every transport-visible lifecycle field. The only
 * process-local fields are non-transferable execution handles (Thread and
 * ScheduledFuture), guarded exclusively by the local Entry monitor.
 */
@Component
public class AiRequestRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRequestRegistry.class);
    private static final Duration TERMINAL_RETENTION = Duration.ofMinutes(30);
    private static final Duration DEFAULT_STOP_GRACE_PERIOD = Duration.ofSeconds(30);
    private static final Duration DISTRIBUTED_RECONCILIATION_LAG = Duration.ofSeconds(1);
    private static final Duration MAINTENANCE_LEASE = Duration.ofMinutes(5);
    private static final int MAX_REGISTRY_ENTRIES = 10_000;
    private static final int MAX_ACTIVE_REQUESTS_PER_USER = 8;

    private final Map<String, Entry> localRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Duration stopGracePeriod;
    private final AiRequestStateStore stateStore;
    private final String instanceId;

    @Autowired
    public AiRequestRegistry(@Qualifier("scoreAiLifecycleScheduler") ScheduledExecutorService scheduler,
                             AiRequestStateStore stateStore) {
        this(scheduler, DEFAULT_STOP_GRACE_PERIOD, stateStore);
    }

    AiRequestRegistry(ScheduledExecutorService scheduler, Duration stopGracePeriod) {
        this(scheduler, stopGracePeriod, new InMemoryAiRequestStateStore());
    }

    AiRequestRegistry(ScheduledExecutorService scheduler, Duration stopGracePeriod,
                      AiRequestStateStore stateStore) {
        this.scheduler = scheduler;
        this.stopGracePeriod = stopGracePeriod;
        this.stateStore = stateStore;
        this.instanceId = UUID.randomUUID().toString();
        this.stateStore.addStopListener(this::stopRequested);
    }

    public AiRequestRegistry() {
        this(java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("score-ai-registry-test-", 0).daemon(true).factory()),
                DEFAULT_STOP_GRACE_PERIOD, new InMemoryAiRequestStateStore());
    }

    public Entry register(String requestId, String conversationId, ScoreUser requester, Instant deadline) {
        String appUserId = requester.userId().value().toString();
        long generation = ThreadLocalRandom.current().nextLong(1L, 1L << 53);
        Instant now = Instant.now();
        Instant expiresAt = deadline.plus(stopGracePeriod).plus(TERMINAL_RETENTION);
        AiSharedRequestState state = new AiSharedRequestState(
                requestId, conversationId, appUserId, instanceId, generation,
                deadline, expiresAt, now, now, null, null,
                "REGISTERED", null, "CANCELLED", null, null, null,
                0L, false, 0, false);

        stateStore.withGlobalLock(storage -> {
            var states = storage.values();
            if (states.size() >= MAX_REGISTRY_ENTRIES) {
                throw new IllegalStateException("The AI request registry is at capacity. Try again later.");
            }
            long activeForUser = states.stream()
                    .filter(candidate -> appUserId.equals(candidate.appUserId()))
                    .filter(candidate -> isLogicallyActive(candidate, now))
                    .count();
            if (activeForUser >= MAX_ACTIVE_REQUESTS_PER_USER) {
                throw new IllegalStateException("Too many AI requests are already active for this user.");
            }
            if (storage.get(requestId) != null) {
                throw new IllegalArgumentException("An AI request with this requestId already exists.");
            }
            if (conversationId != null && (storage.maintenanceOwner(conversationId) != null
                    || states.stream().anyMatch(candidate -> conversationId.equals(candidate.conversationId())
                    && isLogicallyActive(candidate, now)))) {
                throw new IllegalStateException("This AI conversation already has an active request.");
            }
            storage.put(state);
            return null;
        });

        Entry entry = new Entry(requestId, generation);
        if (localRequests.putIfAbsent(requestId, entry) != null) {
            rollbackRegistration(state);
            throw new IllegalArgumentException("An AI request with this requestId already exists.");
        }
        long delay = Math.max(0L, Duration.between(Instant.now(), deadline).toMillis());
        try {
            ScheduledFuture<?> deadlineTask = scheduler.schedule(
                    () -> dispatch(entry, "deadline", () -> requestTimeout(entry)),
                    delay, TimeUnit.MILLISECONDS);
            synchronized (entry) {
                if (sharedState(entry).map(AiSharedRequestState::terminal).orElse(true)) {
                    deadlineTask.cancel(false);
                } else {
                    entry.deadlineTask = deadlineTask;
                }
            }
        } catch (RejectedExecutionException exception) {
            localRequests.remove(requestId, entry);
            rollbackRegistration(state);
            throw exception;
        }
        return entry;
    }

    public void bindConversation(Entry entry, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("The prepared AI request must have a conversation ID.");
        }
        stateStore.withGlobalAndRequestLock(entry.requestId, storage -> {
            AiSharedRequestState state = exactOwnerState(storage, entry);
            if (!"REGISTERED".equals(state.status())) {
                throw new IllegalStateException("The AI request stopped before preparation completed.");
            }
            if (state.conversationId() != null && !state.conversationId().equals(conversationId)) {
                throw new IllegalStateException("The prepared AI conversation does not match its reservation.");
            }
            if (state.conversationId() == null) {
                if (storage.maintenanceOwner(conversationId) != null
                        || storage.values().stream().anyMatch(candidate -> !entry.requestId.equals(candidate.requestId())
                        && conversationId.equals(candidate.conversationId())
                        && isLogicallyActive(candidate, Instant.now()))) {
                    throw new IllegalStateException("This AI conversation already has an active request.");
                }
                storage.put(state.withConversation(conversationId, Instant.now()));
            }
            return null;
        });
    }

    /** Prevents model changes or deletion from racing request admission on any instance. */
    public <T> T whileConversationIdle(String conversationId, Supplier<T> action) {
        String token = instanceId + ":maintenance:" + UUID.randomUUID();
        stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            if (storage.maintenanceOwner(conversationId) != null
                    || storage.values().stream().anyMatch(state -> conversationId.equals(state.conversationId())
                    && isLogicallyActive(state, now))) {
                throw new IllegalStateException("Stop the active AI request before changing its conversation.");
            }
            storage.putMaintenance(conversationId, token, MAINTENANCE_LEASE);
            return null;
        });
        try {
            return action.get();
        } finally {
            stateStore.withGlobalLock(storage -> {
                storage.removeMaintenance(conversationId, token);
                return null;
            });
        }
    }

    public boolean start(Entry entry) {
        synchronized (entry) {
            if (entry.workerThread != null || entry.locallyTerminal) {
                return false;
            }
            entry.workerThread = Thread.currentThread();
            boolean started = stateStore.withRequestLock(entry.requestId, storage -> {
                AiSharedRequestState state = storage.get(entry.requestId);
                if (!matchesOwner(state, entry) || !"REGISTERED".equals(state.status())) {
                    return false;
                }
                storage.put(state.started(Instant.now()));
                return true;
            });
            if (!started) {
                entry.workerThread = null;
            }
            return started;
        }
    }

    public void complete(Entry entry) { finish(entry, null); }

    public void fail(Entry entry, Throwable throwable) { finish(entry, throwable); }

    public String finish(Entry entry, Throwable throwable) {
        AiSharedRequestState terminal = stateStore.withRequestLock(entry.requestId, storage -> {
            AiSharedRequestState state = storage.get(entry.requestId);
            if (!matchesOwner(state, entry)) {
                return state;
            }
            if (state.terminal()) {
                return state;
            }
            Instant now = Instant.now();
            AiSharedRequestState finished;
            if ("CANCELLING".equals(state.status())) {
                String target = state.mutationOutcomeUncertain()
                        ? "UNKNOWN_RECONCILIATION_REQUIRED" : state.terminalTarget();
                String reason = state.mutationOutcomeUncertain()
                        ? "MUTATION_OUTCOME_UNCERTAIN" : state.statusReason();
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

    private void requestTimeout(Entry entry) {
        boolean workerPresent;
        synchronized (entry) {
            workerPresent = entry.workerThread != null;
        }
        AiSharedRequestState state = stateStore.withRequestLock(entry.requestId, storage -> {
            AiSharedRequestState current = storage.get(entry.requestId);
            if (!matchesOwner(current, entry) || current.terminal()
                    || "CANCELLING".equals(current.status())) {
                return null;
            }
            AiSharedRequestState timed = current.timingOut(Instant.now(), workerPresent);
            storage.put(timed);
            return timed;
        });
        applyStopState(entry, state);
    }

    private void scheduleStopWatchdog(Entry entry) {
        synchronized (entry) {
            if (entry.stopWatchdogTask != null) {
                entry.stopWatchdogTask.cancel(false);
            }
            try {
                entry.stopWatchdogTask = scheduler.schedule(
                        () -> dispatch(entry, "stop watchdog", () -> stopWatchdog(entry)),
                        stopGracePeriod.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException exception) {
                LOGGER.error("Could not schedule the AI stop watchdog for request {}; reconciling immediately",
                        entry.requestId, exception);
                dispatch(entry, "stop watchdog fallback", () -> stopWatchdog(entry));
            }
        }
    }

    private void stopWatchdog(Entry entry) {
        Thread worker;
        synchronized (entry) {
            worker = entry.workerThread;
            entry.workerThread = null;
        }
        if (worker == null) {
            return;
        }
        worker.interrupt();
        AiSharedRequestState state = stateStore.withRequestLock(entry.requestId, storage -> {
            AiSharedRequestState current = storage.get(entry.requestId);
            if (!matchesOwner(current, entry) || !"CANCELLING".equals(current.status())) {
                return current;
            }
            AiSharedRequestState reconciled = current.terminal(
                    "UNKNOWN_RECONCILIATION_REQUIRED", "WORKER_STOP_TIMEOUT", Instant.now());
            storage.put(reconciled);
            return reconciled;
        });
        if (state != null && state.terminal()) {
            markLocalTerminal(entry);
        }
    }

    void dispatch(Entry entry, String operation, Runnable task) {
        try {
            Thread.startVirtualThread(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    LOGGER.error("AI request {} lifecycle {} task failed",
                            entry.requestId, operation, failure);
                    lifecycleTaskFailed(entry, operation);
                }
            });
        } catch (RuntimeException | Error failure) {
            LOGGER.error("Could not dispatch AI request {} lifecycle {} task",
                    entry.requestId, operation, failure);
            lifecycleTaskFailed(entry, operation);
        }
    }

    private void lifecycleTaskFailed(Entry entry, String operation) {
        Thread worker;
        synchronized (entry) {
            worker = entry.workerThread;
            entry.workerThread = null;
        }
        if (worker != null) {
            worker.interrupt();
        }
        AiSharedRequestState state = stateStore.withRequestLock(entry.requestId, storage -> {
            AiSharedRequestState current = storage.get(entry.requestId);
            if (!matchesOwner(current, entry) || current.terminal()) {
                return current;
            }
            String reason = operation.toUpperCase(java.util.Locale.ROOT).replace(' ', '_') + "_FAILED";
            AiSharedRequestState failed = current.terminal(
                    "UNKNOWN_RECONCILIATION_REQUIRED", reason, Instant.now());
            storage.put(failed);
            return failed;
        });
        if (state != null && state.terminal()) {
            markLocalTerminal(entry);
        }
    }

    public boolean mutationStarted(String requestId) {
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            if (state == null || !instanceId.equals(state.workerInstanceId())
                    || !"RUNNING".equals(state.status())) {
                return false;
            }
            storage.put(state.mutationStarted(Instant.now()));
            return true;
        });
    }

    public void mutationFinished(String requestId) {
        stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            if (state != null && instanceId.equals(state.workerInstanceId())) {
                storage.put(state.mutationFinished(Instant.now()));
            }
            return null;
        });
    }

    public boolean hasActiveConversation(String conversationId) {
        return stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            return storage.maintenanceOwner(conversationId) != null
                    || storage.values().stream().anyMatch(state -> conversationId.equals(state.conversationId())
                    && isLogicallyActive(state, now));
        });
    }

    public boolean shouldDiscardResult(String requestId) {
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            return state == null || "CANCELLING".equals(state.status())
                    || state.terminal() && !"COMPLETED".equals(state.status());
        });
    }

    /** Atomically fences cluster-wide cancellation/deadline against final persistence. */
    public boolean commitResult(String requestId, Runnable persistence) {
        Entry entry = localRequests.get(requestId);
        if (entry == null) {
            return false;
        }
        boolean committed;
        synchronized (entry) {
            committed = stateStore.withRequestLock(requestId, storage -> {
                AiSharedRequestState state = storage.get(requestId);
                if (!matchesOwner(state, entry) || !"RUNNING".equals(state.status())) {
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
        String appUserId = requester.userId().value().toString();
        CancelOutcome outcome = stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = ownedState(storage, requestId, appUserId);
            state = reconcileOverdue(storage, state, Instant.now());
            if (conversationId != null || expectedGeneration != null) {
                if (!java.util.Objects.equals(state.conversationId(), conversationId)
                        || expectedGeneration == null || expectedGeneration != state.generation()) {
                    return new CancelOutcome(cancellationResponse(state, cancellationRequestId,
                            "STALE_GENERATION", false), false);
                }
            }
            if (state.terminal()) {
                return new CancelOutcome(cancellationResponse(state, cancellationRequestId,
                        "ALREADY_TERMINAL", false), false);
            }
            if ("CANCELLING".equals(state.status())) {
                return new CancelOutcome(cancellationResponse(state, cancellationRequestId,
                        "ALREADY_CANCELLING", true), true);
            }
            Instant now = Instant.now();
            AiSharedRequestState cancelling = state.cancelling(
                    cancellationRequestId, "CANCELLED", now);
            if ("REGISTERED".equals(state.status())) {
                cancelling = cancelling.terminal("CANCELLED", null, now);
                storage.put(cancelling);
                return new CancelOutcome(cancellationResponse(cancelling, cancellationRequestId,
                        "CANCELLED", true), true);
            }
            storage.put(cancelling);
            return new CancelOutcome(cancellationResponse(cancelling, cancellationRequestId,
                    "ACKNOWLEDGED", true), true);
        });
        if (outcome.signalOwner()) {
            stateStore.publishStop(requestId,
                    outcome.response().generation() != null ? outcome.response().generation() : -1L);
        }
        return outcome.response();
    }

    public AiCancellationResponse cancel(String requestId, String cancellationRequestId,
                                         ScoreUser requester) {
        return cancel(requestId, cancellationRequestId, null, null, requester);
    }

    public AiPublicExecutionRequestStatus status(String requestId, ScoreUser requester) {
        String appUserId = requester.userId().value().toString();
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = ownedState(storage, requestId, appUserId);
            return reconcileOverdue(storage, state, Instant.now()).snapshot();
        });
    }

    public Optional<AiPublicExecutionRequestStatus> active(ScoreUser requester) {
        String appUserId = requester.userId().value().toString();
        return stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            return storage.values().stream()
                    .filter(state -> appUserId.equals(state.appUserId()))
                    .filter(state -> isLogicallyActive(state, now))
                    .max(Comparator.comparing(AiSharedRequestState::createdAt))
                    .map(AiSharedRequestState::snapshot);
        });
    }

    public String cancellationRequestId(Entry entry) {
        return sharedState(entry).map(AiSharedRequestState::cancellationRequestId).orElse(null);
    }

    private void stopRequested(AiRequestStateStore.StopSignal signal) {
        Entry entry = localRequests.get(signal.requestId());
        if (entry == null || entry.generation != signal.generation()) {
            return;
        }
        AiSharedRequestState state = sharedState(entry).orElse(null);
        applyStopState(entry, state);
    }

    private void applyStopState(Entry entry, AiSharedRequestState state) {
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
        if (worker != null) {
            worker.interrupt();
        }
        if (watchdog) {
            scheduleStopWatchdog(entry);
        }
        if (state.terminal()) {
            markLocalTerminal(entry);
        }
    }

    private void markLocalTerminal(Entry entry) {
        synchronized (entry) {
            entry.workerThread = null;
            if (entry.deadlineTask != null) {
                entry.deadlineTask.cancel(false);
                entry.deadlineTask = null;
            }
            if (entry.stopWatchdogTask != null) {
                entry.stopWatchdogTask.cancel(false);
                entry.stopWatchdogTask = null;
            }
            if (entry.locallyTerminal) {
                return;
            }
            entry.locallyTerminal = true;
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

    private Optional<AiSharedRequestState> sharedState(Entry entry) {
        return Optional.ofNullable(stateStore.withRequestLock(
                entry.requestId, storage -> storage.get(entry.requestId)));
    }

    private void rollbackRegistration(AiSharedRequestState expected) {
        stateStore.withRequestLock(expected.requestId(), storage -> {
            AiSharedRequestState current = storage.get(expected.requestId());
            if (current != null && current.generation() == expected.generation()
                    && instanceId.equals(current.workerInstanceId())) {
                storage.remove(expected.requestId());
            }
            return null;
        });
    }

    private AiSharedRequestState exactOwnerState(AiRequestStateStore.Storage storage, Entry entry) {
        AiSharedRequestState state = storage.get(entry.requestId);
        if (!matchesOwner(state, entry)) {
            throw new IllegalStateException("The AI request reservation is no longer owned by this instance.");
        }
        return state;
    }

    private boolean matchesOwner(AiSharedRequestState state, Entry entry) {
        return state != null && state.generation() == entry.generation
                && instanceId.equals(state.workerInstanceId());
    }

    private AiSharedRequestState ownedState(AiRequestStateStore.Storage storage,
                                             String requestId, String appUserId) {
        AiSharedRequestState state = storage.get(requestId);
        if (state == null || !appUserId.equals(state.appUserId())) {
            throw new AccessDeniedException(
                    "AI request does not exist or is not owned by the signed-in user.");
        }
        return state;
    }

    private AiSharedRequestState reconcileOverdue(AiRequestStateStore.Storage storage,
                                                   AiSharedRequestState state, Instant now) {
        Instant reconciliationAt = reconciliationAt(state);
        if (state.terminal() || now.isBefore(reconciliationAt)) {
            return state;
        }
        String terminalStatus;
        String reason;
        if ("CANCELLING".equals(state.status())) {
            terminalStatus = state.mutationOutcomeUncertain()
                    ? "UNKNOWN_RECONCILIATION_REQUIRED" : state.terminalTarget();
            reason = state.mutationOutcomeUncertain()
                    ? "MUTATION_OUTCOME_UNCERTAIN" : "WORKER_INSTANCE_UNAVAILABLE";
        } else {
            terminalStatus = state.mutationObserved()
                    ? "UNKNOWN_RECONCILIATION_REQUIRED" : "TIMED_OUT";
            reason = "WORKER_INSTANCE_UNAVAILABLE";
        }
        AiSharedRequestState reconciled = state.terminal(terminalStatus, reason, now);
        storage.put(reconciled);
        return reconciled;
    }

    private boolean isLogicallyActive(AiSharedRequestState state, Instant now) {
        return !state.terminal() && now.isBefore(reconciliationAt(state));
    }

    private Instant reconciliationAt(AiSharedRequestState state) {
        Instant localWatchdogAt = "CANCELLING".equals(state.status()) && state.cancellationRequestedAt() != null
                ? state.cancellationRequestedAt().plus(stopGracePeriod)
                : state.deadline().plus(stopGracePeriod);
        // Give the owning instance's watchdog a deterministic opportunity to report a
        // stuck live worker before another instance treats the owner as unavailable.
        return localWatchdogAt.plus(DISTRIBUTED_RECONCILIATION_LAG);
    }

    private AiCancellationResponse cancellationResponse(
            AiSharedRequestState state, String requestedCancellationId,
            String disposition, boolean acknowledged) {
        return new AiCancellationResponse(state.requestId(), state.conversationId(), state.generation(),
                requestedCancellationId, state.cancellationRequestId(), disposition, state.status(),
                acknowledged, state.terminal(), state.lastEventSequence(),
                state.cancellationRequestedAt(), state.cancellationAcknowledgedAt(),
                state.deadline(), state.terminalAt());
    }

    private record CancelOutcome(AiCancellationResponse response, boolean signalOwner) {}

    public static final class Entry {
        private final String requestId;
        private final long generation;
        private Thread workerThread;
        private ScheduledFuture<?> deadlineTask;
        private ScheduledFuture<?> stopWatchdogTask;
        private boolean locallyTerminal;

        private Entry(String requestId, long generation) {
            this.requestId = requestId;
            this.generation = generation;
        }

        public String requestId() { return requestId; }
        public long generation() { return generation; }
        synchronized boolean hasWorkerThread() { return workerThread != null; }
        synchronized boolean hasDeadlineTask() { return deadlineTask != null; }
    }
}
