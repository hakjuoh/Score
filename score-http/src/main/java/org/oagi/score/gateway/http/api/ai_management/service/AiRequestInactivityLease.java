package org.oagi.score.gateway.http.api.ai_management.service;

import java.time.Duration;

/** Monotonic rolling inactivity window for one request-registry entry. */
final class AiRequestInactivityLease {

    private static final long MAX_REVIEW_NANOS = Duration.ofSeconds(30).toNanos();
    private static final long MIN_REVIEW_NANOS = Duration.ofMillis(10).toNanos();

    private final long timeoutNanos;
    private final long reviewIntervalNanos;
    private long lastProgressNanos;
    private boolean expired;

    AiRequestInactivityLease(Duration timeout, long startedNanos) {
        this.timeoutNanos = positiveNanos(timeout);
        long quarter = Math.max(1L, timeoutNanos / 4L);
        this.reviewIntervalNanos = Math.min(MAX_REVIEW_NANOS,
                Math.max(MIN_REVIEW_NANOS, quarter));
        this.lastProgressNanos = startedNanos;
    }

    synchronized void progress(long nowNanos) {
        if (!expired) lastProgressNanos = nowNanos;
    }

    synchronized long remainingNanos(long nowNanos) {
        if (expired) return 0L;
        return timeoutNanos - (nowNanos - lastProgressNanos);
    }

    synchronized Review review(long nowNanos, boolean definiteWorkInFlight) {
        long remaining = remainingNanos(nowNanos);
        if (remaining <= 0 && definiteWorkInFlight) {
            lastProgressNanos = nowNanos;
            remaining = timeoutNanos;
        }
        if (remaining <= 0) expired = true;
        return new Review(expired, Math.max(0L, remaining));
    }

    synchronized boolean expired() {
        return expired;
    }

    synchronized void renew(long nowNanos) {
        expired = false;
        lastProgressNanos = nowNanos;
    }

    long firstReviewNanos() {
        return Math.min(timeoutNanos, reviewIntervalNanos);
    }

    long nextReviewNanos(long remainingNanos) {
        return Math.min(remainingNanos, reviewIntervalNanos);
    }

    private static long positiveNanos(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException tooLarge) {
            return Long.MAX_VALUE;
        }
    }

    record Review(boolean expired, long remainingNanos) { }
}
