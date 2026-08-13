package org.oagi.score.gateway.http.api.activity_management.service;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityActor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AsyncScoreActivityEventPublisherTest {

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void writesOutsideATransactionWithoutDoingDestinationIoOnTheCaller() {
        AtomicInteger writes = new AtomicInteger();
        TestSink sink = new TestSink(writes, false);
        AtomicInteger scheduled = new AtomicInteger();
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                sink,
                command -> {
                    scheduled.incrementAndGet();
                    command.run();
                });

        publisher.publish(event());

        assertThat(scheduled).hasValue(1);
        assertThat(writes).hasValue(1);
    }

    @Test
    void waitsForCommitBeforeSchedulingTheEvent() {
        AtomicInteger writes = new AtomicInteger();
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                new TestSink(writes, false),
                Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event());

        assertThat(writes).hasValue(0);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertThat(writes).hasValue(1);
    }

    @Test
    void doesNotScheduleAnEventWhenTheTransactionDoesNotCommit() {
        AtomicInteger writes = new AtomicInteger();
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                new TestSink(writes, false),
                Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event());
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(writes).hasValue(0);
    }

    @Test
    void immediatePublishingBypassesATransactionThatRollsBack() {
        AtomicInteger writes = new AtomicInteger();
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                new TestSink(writes, false),
                Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishImmediately(event());
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(writes).hasValue(1);
    }

    @Test
    void destinationFailureDoesNotEscapeToTheUserOperation() {
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                new TestSink(new AtomicInteger(), true),
                Runnable::run);

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();
    }

    @Test
    void aRejectedLocalQueueDoesNotEscapeToTheUserOperation() {
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                new TestSink(new AtomicInteger(), false),
                command -> {
                    throw new RejectedExecutionException("full");
                });

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();
    }

    @Test
    void productionExecutorPerformsDestinationIoOnABackgroundThread() throws Exception {
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> sinkThread = new AtomicReference<>();
        CountDownLatch written = new CountDownLatch(1);
        ScoreActivityEventSink sink = new ScoreActivityEventSink() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public void write(ScoreActivityEvent event) {
                sinkThread.set(Thread.currentThread().getName());
                written.countDown();
            }
        };
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                sink, 1, 8, Duration.ofSeconds(1));

        publisher.publish(event());

        assertThat(written.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(sinkThread.get()).startsWith("score-activity-event-").isNotEqualTo(callerThread);
        publisher.close();
    }

    @Test
    void carriesTheSubmittingOpenTelemetryContextToTheWorker() {
        ContextKey<String> key = ContextKey.named("score-test-correlation");
        AtomicReference<String> observed = new AtomicReference<>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                new ScoreActivityEventSink() {
                    @Override
                    public String type() {
                        return "test";
                    }

                    @Override
                    public void write(ScoreActivityEvent event) {
                        observed.set(Context.current().get(key));
                    }
                },
                scheduled::set);

        try (Scope ignored = Context.current().with(key, "request-42").makeCurrent()) {
            publisher.publish(event());
        }
        scheduled.get().run();

        assertThat(observed).hasValue("request-42");
    }

    @Test
    void aFullProductionQueueDropsInsteadOfBlockingTheCaller() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch twoWritesStarted = new CountDownLatch(2);
        ScoreActivityEventSink sink = new ScoreActivityEventSink() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public void write(ScoreActivityEvent event) throws InterruptedException {
                twoWritesStarted.countDown();
                firstWriteStarted.countDown();
                releaseFirstWrite.await();
            }
        };
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                sink, 1, 1, Duration.ofSeconds(1));
        publisher.publish(event());
        assertThat(firstWriteStarted.await(1, TimeUnit.SECONDS)).isTrue();
        publisher.publish(event());

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();
        releaseFirstWrite.countDown();
        assertThat(twoWritesStarted.await(1, TimeUnit.SECONDS)).isTrue();
        publisher.close();
    }

    @Test
    void closeUsesABoundedWaitAndInterruptsAStuckWorker() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ScoreActivityEventSink sink = new ScoreActivityEventSink() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public void write(ScoreActivityEvent event) throws InterruptedException {
                writeStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
            }
        };
        AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                sink, 1, 1, Duration.ofMillis(25));
        publisher.publish(event());
        assertThat(writeStarted.await(1, TimeUnit.SECONDS)).isTrue();

        publisher.close();

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private static ScoreActivityEvent event() {
        return new ScoreActivityEvent(
                "1.0",
                "cc2f52bb-cffa-42c6-b673-6e25aba5ebda",
                Instant.parse("2026-08-06T12:00:00Z"),
                "acc.update",
                "SCORE_HTTP_API",
                "SUCCEEDED",
                new ScoreActivityActor("7", "developer"),
                List.of(new ScoreActivityTarget("ACC", "42", null, null, "PRIMARY")),
                Map.of(),
                ScoreActivityContext.empty());
    }

    private record TestSink(AtomicInteger writes, boolean fail) implements ScoreActivityEventSink {
        @Override
        public String type() {
            return "test";
        }

        @Override
        public void write(ScoreActivityEvent event) {
            writes.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("destination unavailable");
            }
        }
    }
}
