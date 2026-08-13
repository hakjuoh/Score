package org.oagi.score.gateway.http.api.activity_management.service;

import io.opentelemetry.context.Context;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

/**
 * Best-effort publisher that defers transactional events until commit and performs destination I/O
 * on a bounded background executor. A full queue or a destination failure drops only the activity.
 */
public final class AsyncScoreActivityEventPublisher implements ScoreActivityEventPublisher {

    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScoreActivityEventSink sink;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Duration shutdownTimeout;
    private final AtomicLong nextWarningNanos = new AtomicLong();
    private final AtomicLong droppedSinceWarning = new AtomicLong();

    public AsyncScoreActivityEventPublisher(
            ScoreActivityEventSink sink,
            int workerCount,
            int queueCapacity) {
        this(sink, workerCount, queueCapacity, Duration.ofSeconds(2));
    }

    public AsyncScoreActivityEventPublisher(
            ScoreActivityEventSink sink,
            int workerCount,
            int queueCapacity,
            Duration shutdownTimeout) {
        this(
                sink,
                new ThreadPoolExecutor(
                        positive(workerCount, "workerCount"),
                        positive(workerCount, "workerCount"),
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(positive(queueCapacity, "queueCapacity")),
                        Thread.ofPlatform().name("score-activity-event-", 0).daemon(true).factory(),
                        new ThreadPoolExecutor.AbortPolicy()),
                true,
                positive(shutdownTimeout, "shutdownTimeout"));
    }

    AsyncScoreActivityEventPublisher(ScoreActivityEventSink sink, Executor executor) {
        this(sink, executor, false, Duration.ZERO);
    }

    private AsyncScoreActivityEventPublisher(
            ScoreActivityEventSink sink,
            Executor executor,
            boolean ownsExecutor,
            Duration shutdownTimeout) {
        this.sink = requireNonNull(sink, "sink must not be null");
        this.executor = requireNonNull(executor, "executor must not be null");
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void publish(ScoreActivityEvent event) {
        if (event == null) {
            return;
        }
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        enqueue(event);
                    }
                });
            } else {
                enqueue(event);
            }
        } catch (RuntimeException exception) {
            recordDrop("transaction scheduling failed", event.eventId(), exception);
        }
    }

    @Override
    public void publishImmediately(ScoreActivityEvent event) {
        if (event != null) {
            enqueue(event);
        }
    }

    private void enqueue(ScoreActivityEvent event) {
        try {
            executor.execute(Context.current().wrap(() -> write(event)));
        } catch (RuntimeException exception) {
            recordDrop("local queue unavailable", event.eventId(), exception);
        }
    }

    private void write(ScoreActivityEvent event) {
        try {
            sink.write(event);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException
                    && ownedExecutor != null
                    && ownedExecutor.isShutdown()) {
                Thread.currentThread().interrupt();
                return;
            }
            recordDrop("sink '" + sink.type() + "' failed", event.eventId(), exception);
        }
    }

    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
            try {
                if (!ownedExecutor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    int abandoned = ownedExecutor.shutdownNow().size();
                    if (abandoned > 0) {
                        logger.warn("Discarded {} queued SCORE activity event(s) during shutdown.", abandoned);
                    }
                    if (!ownedExecutor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        logger.warn("SCORE activity worker did not terminate after forced shutdown.");
                    }
                }
            } catch (InterruptedException exception) {
                ownedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recordDrop(String reason, String eventId, Exception exception) {
        droppedSinceWarning.incrementAndGet();
        long now = System.nanoTime();
        long next = nextWarningNanos.get();
        if ((next != 0 && now < next)
                || !nextWarningNanos.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            return;
        }
        long dropped = droppedSinceWarning.getAndSet(0);
        logger.warn("Dropped {} SCORE activity event(s); latest reason={}, eventId={}, causeType={}.",
                dropped, reason, eventId, exception.getClass().getSimpleName());
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        requireNonNull(value, name + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}
