package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSpecialistAdmissionGateTest {

    @Test
    void enforcesGlobalAndPolicyBoundedUserLimits() throws Exception {
        AiSpecialistAdmissionGate gate = new AiSpecialistAdmissionGate(2, 4);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int index = 0; index < 6; index++) {
            Thread worker = Thread.startVirtualThread(() -> {
                await(start);
                try (var ignored = gate.acquire("user-1", 2, () -> { })) {
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(Duration.ofMillis(10));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                }
            });
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) worker.join();

        assertThat(maximum).hasValue(2);
    }

    @Test
    void waitingCheckpointCanCancelWithoutLeakingPermit() throws Exception {
        AiSpecialistAdmissionGate gate = new AiSpecialistAdmissionGate(1, 1);
        AiSpecialistAdmissionGate.Lease occupied = gate.acquire("user-1", 1, () -> { });
        AtomicBoolean cancelled = new AtomicBoolean();
        FutureTask<Void> waiting = new FutureTask<>(() -> {
            gate.acquire("user-1", 1, () -> {
                if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            });
            return null;
        });
        Thread worker = Thread.startVirtualThread(waiting);
        cancelled.set(true);

        assertThatThrownBy(waiting::get).hasCauseInstanceOf(
                java.util.concurrent.CancellationException.class);
        worker.join();
        occupied.close();

        try (var ignored = gate.acquire("user-1", 1, () -> { })) {
            assertThat(ignored).isNotNull();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        }
    }
}
