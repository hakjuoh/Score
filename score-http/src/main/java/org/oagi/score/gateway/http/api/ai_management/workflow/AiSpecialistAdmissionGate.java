package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Process-local admission gate for concurrently executing specialist agents.
 * Root request concurrency remains cluster-wide in the shared request registry.
 */
final class AiSpecialistAdmissionGate {

    private static final long RETRY_NANOS = 1_000_000L;

    private final Semaphore global;
    private final int perUserLimit;
    private final ScoreAiObservability observability;
    private final ConcurrentHashMap<String, UserCounter> users = new ConcurrentHashMap<>();

    AiSpecialistAdmissionGate(int globalLimit, int perUserLimit) {
        this(globalLimit, perUserLimit, ScoreAiObservability.noop());
    }

    AiSpecialistAdmissionGate(int globalLimit, int perUserLimit,
                              ScoreAiObservability observability) {
        if (globalLimit < 1 || perUserLimit < 1) {
            throw new IllegalArgumentException("Specialist concurrency limits must be positive.");
        }
        this.global = new Semaphore(globalLimit, true);
        this.perUserLimit = perUserLimit;
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    Lease acquire(String requesterId, int policyMaximumAgents, Runnable checkpoint) {
        String userKey = Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(checkpoint, "checkpoint");
        int effectiveUserLimit = Math.min(perUserLimit, Math.max(1, policyMaximumAgents));
        UserCounter user = users.computeIfAbsent(userKey, ignored -> new UserCounter());
        long startedNanos = System.nanoTime();

        try {
            while (true) {
                checkpoint.run();
                boolean userAcquired = user.tryAcquire(effectiveUserLimit);
                if (userAcquired) {
                    if (global.tryAcquire()) {
                        observability.specialistAdmissionWait(System.nanoTime() - startedNanos);
                        return new Lease(this, userKey, user);
                    }
                    user.release();
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException(
                            "Specialist admission was interrupted.");
                }
                LockSupport.parkNanos(RETRY_NANOS);
            }
        } catch (RuntimeException | Error failure) {
            observability.specialistAdmissionRejected();
            throw failure;
        }
    }

    private void release(String requesterId, UserCounter user) {
        global.release();
        if (user.release() == 0) users.remove(requesterId, user);
    }

    static final class Lease implements AutoCloseable {
        private final AiSpecialistAdmissionGate owner;
        private final String requesterId;
        private final UserCounter user;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(AiSpecialistAdmissionGate owner, String requesterId, UserCounter user) {
            this.owner = owner;
            this.requesterId = requesterId;
            this.user = user;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.release(requesterId, user);
        }
    }

    private static final class UserCounter {
        private int active;

        synchronized boolean tryAcquire(int limit) {
            if (active >= limit) return false;
            active++;
            return true;
        }

        synchronized int release() {
            if (active < 1) throw new IllegalStateException("Specialist permit underflow.");
            return --active;
        }
    }
}
