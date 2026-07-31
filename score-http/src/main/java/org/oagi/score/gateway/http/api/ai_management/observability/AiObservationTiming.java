package org.oagi.score.gateway.http.api.ai_management.observability;

import java.time.Duration;

/** Monotonic elapsed-time conversion shared by observability projections. */
final class AiObservationTiming {

    private AiObservationTiming() {
    }

    static double elapsedMillis(long startedNanos) {
        return nanosToMillis(elapsedNanos(startedNanos));
    }

    static double elapsedSeconds(long startedNanos) {
        return nanosToSeconds(elapsedNanos(startedNanos));
    }

    static double nanosToMillis(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toNanos() / 1_000_000.0;
    }

    static double nanosToSeconds(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toNanos() / 1_000_000_000.0;
    }

    private static long elapsedNanos(long startedNanos) {
        return elapsedNanos(startedNanos, System.nanoTime());
    }

    static long elapsedNanos(long startedNanos, long nowNanos) {
        return Math.max(0L, nowNanos - startedNanos);
    }
}
