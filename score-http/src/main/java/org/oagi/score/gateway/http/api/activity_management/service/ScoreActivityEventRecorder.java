package org.oagi.score.gateway.http.api.activity_management.service;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Isolates optional activity recording from the user operation being recorded.
 *
 * <p>The protected operation includes both event construction and publication. Consequently, a
 * malformed event, unavailable publisher, or full local queue cannot change the result of the
 * business operation.</p>
 */
@Component
public final class ScoreActivityEventRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreActivityEventRecorder.class);
    private static final long WARNING_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final AtomicLong nextWarningAt = new AtomicLong();

    public void record(Runnable recording) {
        requireNonNull(recording, "recording must not be null");
        try {
            recording.run();
        } catch (RuntimeException exception) {
            warnOncePerInterval(exception);
        }
    }

    @Nullable
    public <T> T capture(Supplier<T> recording) {
        requireNonNull(recording, "recording must not be null");
        try {
            return recording.get();
        } catch (RuntimeException exception) {
            warnOncePerInterval(exception);
            return null;
        }
    }

    private void warnOncePerInterval(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long next = nextWarningAt.get();
        if (now >= next && nextWarningAt.compareAndSet(next, now + WARNING_INTERVAL_MILLIS)) {
            LOG.warn("SCORE activity recording failed; the user operation was not affected ({})",
                    exception.getClass().getSimpleName());
        }
    }
}
