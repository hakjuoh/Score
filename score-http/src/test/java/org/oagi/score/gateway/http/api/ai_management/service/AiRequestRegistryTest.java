package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRequestRegistryTest {

    private final ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
            null, false, List.of());

    @Test
    void cancellationBeforeExecutionIsTerminalAndPreventsWorkerStart() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(60));

        var response = registry.cancel("request-1", "cancel-1", user);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.terminal()).isTrue();
        assertThat(registry.start(entry)).isFalse();
        assertThat(registry.status("request-1", user).status()).isEqualTo("CANCELLED");
    }

    @Test
    void refusesToStartTheSameRegistryEntryTwice() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(60));

        assertThat(registry.start(entry)).isTrue();
        assertThat(registry.start(entry)).isFalse();
        registry.complete(entry);
    }

    @Test
    void rollsBackRegistrationWhenTheLifecycleSchedulerRejectsTheDeadline() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        scheduler.shutdownNow();
        AiRequestRegistry registry = new AiRequestRegistry(scheduler, Duration.ofSeconds(1));

        assertThatThrownBy(() -> registry.register("request-rejected", "conversation-rejected", user,
                Instant.now().plusSeconds(60)))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(registry.active(user)).isEmpty();
    }

    @Test
    void exposesOnlyTheOwnersLatestNonTerminalRequestForRefreshRecovery() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry first = registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(60));
        registry.start(first);
        AiRequestRegistry.Entry latest = registry.register("request-2", "conversation-2", user,
                Instant.now().plusSeconds(60));
        registry.start(latest);

        assertThat(registry.active(user)).get()
                .extracting(status -> status.requestId(), status -> status.conversationId(), status -> status.status())
                .containsExactly("request-2", "conversation-2", "RUNNING");

        registry.complete(latest);
        registry.complete(first);
        assertThat(registry.active(user)).isEmpty();
    }

    @Test
    void rejectsConcurrentTurnsForTheSameConversation() {
        AiRequestRegistry registry = new AiRequestRegistry();
        registry.register("request-1", "conversation-1", user, Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> registry.register("request-2", "conversation-1", user,
                Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active request");
    }

    @Test
    void reservesCapacityBeforeANewConversationIsCreatedAndBindsItBeforeStart() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-new", null, user, Instant.now().plusSeconds(60));

        registry.bindConversation(entry, "conversation-created");

        assertThat(registry.status("request-new", user).conversationId())
                .isEqualTo("conversation-created");
        assertThatThrownBy(() -> registry.register("request-racing", "conversation-created", user,
                Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active request");
    }

    @Test
    void conversationMaintenanceBlocksRequestAdmissionUntilItCompletes() {
        AiRequestRegistry registry = new AiRequestRegistry();

        registry.whileConversationIdle("conversation-1", () -> {
            assertThatThrownBy(() -> registry.register("request-1", "conversation-1", user,
                    Instant.now().plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });

        assertThat(registry.register("request-2", "conversation-1", user,
                Instant.now().plusSeconds(60))).isNotNull();
    }

    @Test
    void sharedLeaseRejectsTheSameConversationOnAnotherApplicationInstance() {
        InMemoryAiRequestStateStore sharedState = new InMemoryAiRequestStateStore();
        var firstScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        var secondScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try {
            AiRequestRegistry firstInstance = new AiRequestRegistry(
                    firstScheduler, Duration.ofSeconds(1), sharedState);
            AiRequestRegistry secondInstance = new AiRequestRegistry(
                    secondScheduler, Duration.ofSeconds(1), sharedState);
            AiRequestRegistry.Entry first = firstInstance.register(
                    "request-1", "conversation-1", user, Instant.now().plusSeconds(60));

            assertThatThrownBy(() -> secondInstance.register(
                    "request-2", "conversation-1", user, Instant.now().plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active request");
            assertThat(secondInstance.hasActiveConversation("conversation-1")).isTrue();

            firstInstance.complete(first);
            assertThat(secondInstance.register(
                    "request-2", "conversation-1", user, Instant.now().plusSeconds(60))).isNotNull();
        } finally {
            firstScheduler.shutdownNow();
            secondScheduler.shutdownNow();
        }
    }

    @Test
    void remoteInstanceCanReadRecoverAndCancelTheOwningInstancesWorker() {
        InMemoryAiRequestStateStore sharedState = new InMemoryAiRequestStateStore();
        var firstScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        var secondScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try {
            AiRequestRegistry owner = new AiRequestRegistry(
                    firstScheduler, Duration.ofSeconds(1), sharedState);
            AiRequestRegistry remote = new AiRequestRegistry(
                    secondScheduler, Duration.ofSeconds(1), sharedState);
            AiRequestRegistry.Entry entry = owner.register(
                    "request-1", "conversation-1", user, Instant.now().plusSeconds(60));
            assertThat(owner.start(entry)).isTrue();

            assertThat(remote.active(user)).get()
                    .extracting(status -> status.requestId(), status -> status.status())
                    .containsExactly("request-1", "RUNNING");
            assertThat(remote.status("request-1", user).generation()).isEqualTo(entry.generation());

            var response = remote.cancel("request-1", "cancel-remote", "conversation-1",
                    entry.generation(), user);
            assertThat(response.disposition()).isEqualTo("ACKNOWLEDGED");
            assertThat(Thread.interrupted()).isTrue();
            assertThat(owner.finish(entry, new CancellationException())).isEqualTo("CANCELLED");
            assertThat(remote.status("request-1", user).status()).isEqualTo("CANCELLED");
        } finally {
            firstScheduler.shutdownNow();
            secondScheduler.shutdownNow();
        }
    }

    @Test
    void remoteCancellationReconcilesAfterGraceWhenTheOwnerInstanceIsGone() throws Exception {
        InMemoryAiRequestStateStore sharedState = new InMemoryAiRequestStateStore();
        Instant now = Instant.now();
        AiSharedRequestState abandoned = new AiSharedRequestState(
                "request-abandoned", "conversation-abandoned", user.userId().value().toString(),
                "dead-instance", 9L, now.plusSeconds(60), now.plusSeconds(120),
                now, now, now, null, "RUNNING", null, "CANCELLED",
                null, null, null, 0L, false, 0, false);
        sharedState.withGlobalLock(storage -> {
            storage.put(abandoned);
            return null;
        });
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try {
            AiRequestRegistry remote = new AiRequestRegistry(
                    scheduler, Duration.ofMillis(20), sharedState);

            assertThat(remote.cancel("request-abandoned", "cancel-remote",
                    "conversation-abandoned", 9L, user).status()).isEqualTo("CANCELLING");

            awaitStatus(remote, "request-abandoned", "CANCELLED");
            assertThat(remote.active(user)).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void boundsTheNumberOfActiveRequestsOwnedByOneUser() {
        AiRequestRegistry registry = new AiRequestRegistry();
        for (int index = 0; index < 8; index++) {
            registry.register("request-" + index, "conversation-" + index, user,
                    Instant.now().plusSeconds(60));
        }

        assertThatThrownBy(() -> registry.register("request-9", "conversation-9", user,
                Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many AI requests");
    }

    @Test
    void rejectsAStaleCancellationFenceWithoutStoppingTheRequest() {
        AiRequestRegistry registry = new AiRequestRegistry();
        registry.register("request-1", "conversation-1", user, Instant.now().plusSeconds(60));

        var response = registry.cancel("request-1", "cancel-1", "conversation-other", 1L, user);

        assertThat(response.disposition()).isEqualTo("STALE_GENERATION");
        assertThat(registry.status("request-1", user).status()).isEqualTo("REGISTERED");
    }

    @Test
    void enforcesDeadlineBeforeQueuedWorkCanStart() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusMillis(25));

        Thread.sleep(100);

        assertThat(registry.status("request-1", user).status()).isEqualTo("TIMED_OUT");
        assertThat(registry.start(entry)).isFalse();
        assertThat(entry.hasDeadlineTask()).isFalse();
    }

    @Test
    void runningDeadlineInterruptsWorkButDoesNotClaimTerminalUntilTheWorkerStops() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusMillis(40));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch allowStop = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                started.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    try {
                        allowStop.await();
                    } catch (InterruptedException secondInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.status("request-1", user).status()).isEqualTo("CANCELLING");
            assertThat(registry.status("request-1", user).terminalAt()).isNull();

            allowStop.countDown();
            work.get(1, TimeUnit.SECONDS);
            assertThat(registry.status("request-1", user).status()).isEqualTo("TIMED_OUT");
            assertThat(registry.status("request-1", user).terminalAt()).isNotNull();
        }
    }

    @Test
    void mutationInFlightDuringCancellationRequiresReconciliation() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();
        assertThat(registry.mutationStarted("request-1")).isTrue();

        var response = registry.cancel("request-1", "cancel-1", "conversation-1",
                entry.generation(), user);
        Thread.interrupted();
        String terminal = registry.finish(entry, new CancellationException());

        assertThat(response.status()).isEqualTo("CANCELLING");
        assertThat(response.terminal()).isFalse();
        assertThat(terminal).isEqualTo("UNKNOWN_RECONCILIATION_REQUIRED");
    }

    @Test
    void refusesMutationAccountingForAnUnknownRequest() {
        assertThat(new AiRequestRegistry().mutationStarted("missing-request")).isFalse();
    }

    @Test
    void cancellationAfterACompletedMutationStillRequiresReconciliation() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();
        assertThat(registry.mutationStarted("request-1")).isTrue();
        registry.mutationFinished("request-1");

        var response = registry.cancel("request-1", "cancel-1", "conversation-1",
                entry.generation(), user);
        Thread.interrupted();

        assertThat(response.status()).isEqualTo("CANCELLING");
        assertThat(registry.finish(entry, new CancellationException()))
                .isEqualTo("UNKNOWN_RECONCILIATION_REQUIRED");
    }

    @Test
    void cancellationAndFinalPersistenceAreAtomicallyFenced() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();
        registry.cancel("request-1", "cancel-1", "conversation-1", entry.generation(), user);
        Thread.interrupted();

        assertThat(registry.commitResult("request-1",
                () -> { throw new AssertionError("cancelled result must not be persisted"); })).isFalse();
        assertThat(registry.finish(entry, new CancellationException())).isEqualTo("CANCELLED");
    }

    @Test
    void anEarlierUserCancellationIsNotRelabeledAsTimeoutAtTheDeadline() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusMillis(30));
        assertThat(registry.start(entry)).isTrue();
        registry.cancel("request-1", "cancel-1", "conversation-1", entry.generation(), user);
        Thread.interrupted();

        Thread.sleep(80);

        assertThat(registry.finish(entry, new CancellationException())).isEqualTo("CANCELLED");
        assertThat(registry.status("request-1", user).statusReason()).isNull();
    }

    @Test
    void slowFinalPersistenceCannotBlockOtherRequestDeadlines() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiRequestRegistry registry = new AiRequestRegistry(scheduler, Duration.ofSeconds(5));
            AiRequestRegistry.Entry slow = registry.register("request-slow", "conversation-slow", user,
                    Instant.now().plusMillis(100));
            registry.register("request-deadline", "conversation-deadline", user,
                    Instant.now().plusMillis(180));
            CountDownLatch persistenceStarted = new CountDownLatch(1);
            CountDownLatch releasePersistence = new CountDownLatch(1);

            CompletableFuture<Boolean> commit = CompletableFuture.supplyAsync(() -> {
                assertThat(registry.start(slow)).isTrue();
                return registry.commitResult("request-slow", () -> {
                    persistenceStarted.countDown();
                    try {
                        releasePersistence.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }, executor);

            assertThat(persistenceStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitStatus(registry, "request-deadline", "TIMED_OUT");
            releasePersistence.countDown();
            assertThat(commit.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void watchdogTerminalizesAWorkerThatIgnoresInterrupts() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiRequestRegistry registry = new AiRequestRegistry(scheduler, Duration.ofMillis(50));
            AiRequestRegistry.Entry entry = registry.register("request-stuck", "conversation-stuck", user,
                    Instant.now().plusSeconds(60));
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                started.countDown();
                while (releaseWorker.getCount() > 0) {
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately emulate a provider/tool call that ignores interruption.
                    }
                }
                registry.finish(entry, new CancellationException());
            }, executor);

            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                registry.cancel("request-stuck", "cancel-stuck", "conversation-stuck",
                        entry.generation(), user);

                awaitStatus(registry, "request-stuck", "UNKNOWN_RECONCILIATION_REQUIRED");
                assertThat(registry.status("request-stuck", user).statusReason())
                        .isEqualTo("WORKER_STOP_TIMEOUT");
                assertThat(registry.active(user)).isEmpty();
                assertThat(entry.hasWorkerThread()).isFalse();
            } finally {
                releaseWorker.countDown();
            }
            worker.get(1, TimeUnit.SECONDS);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void lifecycleTaskFailureIsLoggedAndConservativelyTerminalized() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-failed-task", "conversation-failed-task", user,
                Instant.now().plusSeconds(60));

        registry.dispatch(entry, "test dispatch", () -> {
            throw new IllegalStateException("simulated lifecycle failure");
        });

        awaitStatus(registry, "request-failed-task", "UNKNOWN_RECONCILIATION_REQUIRED");
        assertThat(registry.status("request-failed-task", user).statusReason())
                .isEqualTo("TEST_DISPATCH_FAILED");
        assertThat(entry.hasWorkerThread()).isFalse();
    }

    private void awaitStatus(AiRequestRegistry registry, String requestId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (expected.equals(registry.status(requestId, user).status())) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(registry.status(requestId, user).status()).isEqualTo(expected);
    }
}
