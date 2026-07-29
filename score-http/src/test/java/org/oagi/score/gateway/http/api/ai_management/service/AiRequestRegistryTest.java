package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRequestRegistryTest {

    private final ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
            null, false, List.of());

    @Test
    void requestInactivityLeaseRenewsAcrossMultipleOriginalWindows() {
        AiRequestInactivityLease lease = new AiRequestInactivityLease(
                Duration.ofNanos(100), 0L);

        lease.progress(90L);
        assertThat(lease.review(150L, false))
                .isEqualTo(new AiRequestInactivityLease.Review(false, 40L));
        lease.progress(180L);
        assertThat(lease.review(250L, false))
                .isEqualTo(new AiRequestInactivityLease.Review(false, 30L));
        assertThat(lease.review(281L, false).expired()).isTrue();
    }

    @Test
    void definiteInFlightWorkRenewsAnOtherwiseExpiredRequestLease() {
        AiRequestInactivityLease lease = new AiRequestInactivityLease(
                Duration.ofNanos(100), 0L);

        assertThat(lease.review(101L, true))
                .isEqualTo(new AiRequestInactivityLease.Review(false, 100L));
    }

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
    void toolAdmissionRejectsAStopThatWinsTheRequestStateLock() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-tool", "conversation-tool", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();

        registry.admitToolExecution(entry.requestId());
        registry.cancel(entry.requestId(), "cancel-tool", user);
        Thread.interrupted();

        assertThatThrownBy(() -> registry.admitToolExecution(entry.requestId()))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("stopped before Tool execution");
    }

    @Test
    void rollsBackRegistrationWhenTheLifecycleSchedulerRejectsTheInactivityReview() {
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
    void childConfirmationMaintenanceIsBoundToItsActiveRootRequest() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry root = registry.register(
                "request-1", "root-conversation", user, Instant.now().plusSeconds(60));
        AtomicBoolean invoked = new AtomicBoolean();

        assertThatThrownBy(() -> registry.whileRequestAndConversationIdle(
                "request-1", "child-conversation", () -> invoked.getAndSet(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active AI request");
        assertThat(invoked).isFalse();

        registry.cancel("request-1", "cancel-1", user);
        assertThat(registry.start(root)).isFalse();
        assertThat(registry.whileRequestAndConversationIdle(
                "request-1", "child-conversation", () -> "decided"))
                .isEqualTo("decided");
    }

    @Test
    void sharedLeaseRejectsTheSameConversationOnAnotherApplicationInstance() {
        AiRequestStateStore sharedState = AiRequestStateStore.inMemory();
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
        AiRequestStateStore sharedState = AiRequestStateStore.inMemory();
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
        AiRequestStateStore sharedState = AiRequestStateStore.inMemory();
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
    void timesOutInactiveQueuedWorkBeforeItCanStart() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register("request-1", "conversation-1", user,
                Instant.now().plusMillis(25));

        Thread.sleep(100);

        assertThat(registry.status("request-1", user).status()).isEqualTo("TIMED_OUT");
        assertThat(registry.start(entry)).isFalse();
        assertThat(entry.hasLeaseReviewTask()).isFalse();
    }

    @Test
    void runningInactivityTimeoutInterruptsWorkButWaitsForTheWorkerToStop() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch allowStop = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                AiRequestRegistry.Entry entry = registry.register(
                        "request-1", "conversation-1", user,
                        Instant.now().plusMillis(250));
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
    void observableProgressRenewsARequestBeyondItsOriginalDeadline() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-active", "conversation-active", user,
                Instant.now().plusMillis(120));
        Instant originalDeadline = registry.status(entry.requestId(), user).deadline();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                } finally {
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            for (int signal = 0; signal < 8; signal++) {
                Thread.sleep(40);
                registry.progress(entry.requestId());
            }

            assertThat(interrupted.getCount()).isEqualTo(1L);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");
            assertThat(registry.status(entry.requestId(), user).deadline())
                    .isAfter(originalDeadline);

            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            work.get(1, TimeUnit.SECONDS);
            assertThat(registry.status(entry.requestId(), user))
                    .extracting(status -> status.status(), status -> status.statusReason())
                    .containsExactly("TIMED_OUT", "INACTIVITY_TIMEOUT");
        }
    }

    @Test
    void inFlightMutationCannotBeInterruptedByTheRequestInactivityLease() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-mutating", "conversation-mutating", user,
                Instant.now().plusMillis(80));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                } finally {
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.mutationStarted(entry.requestId())).isTrue();
            Thread.sleep(240);

            assertThat(interrupted.getCount()).isEqualTo(1L);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");

            registry.mutationFinished(entry.requestId());
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            work.get(1, TimeUnit.SECONDS);
            assertThat(registry.status(entry.requestId(), user).status())
                    .isEqualTo("UNKNOWN_RECONCILIATION_REQUIRED");
        }
    }

    @Test
    void pendingInteractionUsesItsOwnTimeoutThenStartsAFreshInactivityWindow() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-interaction", "conversation-interaction", user,
                Instant.now().plusMillis(80));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                } finally {
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.interactionStarted(entry.requestId())).isTrue();
            Thread.sleep(240);

            assertThat(interrupted.getCount()).isEqualTo(1L);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");

            registry.interactionFinished(entry.requestId());
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            work.get(1, TimeUnit.SECONDS);
            assertThat(registry.status(entry.requestId(), user).status())
                    .isEqualTo("TIMED_OUT");
        }
    }

    @Test
    void explicitCancellationInterruptsAProtectedInteractionImmediately() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-interaction-cancel", "conversation-interaction-cancel", user,
                Instant.now().plusSeconds(60));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                } finally {
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.interactionStarted(entry.requestId())).isTrue();
            registry.cancel(entry.requestId(), "cancel-interaction",
                    "conversation-interaction-cancel", entry.generation(), user);

            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            work.get(1, TimeUnit.SECONDS);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("CANCELLED");
        }
    }

    @Test
    void mutationAdmissionAtTheTimeoutBoundaryRenewsInsteadOfInterrupting() throws Exception {
        CountDownLatch timeoutReady = new CountDownLatch(1);
        CountDownLatch allowTimeoutTransition = new CountDownLatch(1);
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        AiRequestRegistry registry = new AiRequestRegistry(
                scheduler, Duration.ofSeconds(5), AiRequestStateStore.inMemory(), () -> {
            timeoutReady.countDown();
            try {
                allowTimeoutTransition.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        AiRequestRegistry.Entry entry = registry.register(
                "request-racing-mutation", "conversation-racing-mutation", user,
                Instant.now().plusMillis(80));
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                workerStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    workerInterrupted.countDown();
                } finally {
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(timeoutReady.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.mutationStarted(entry.requestId())).isTrue();
            allowTimeoutTransition.countDown();
            Thread.sleep(120);

            assertThat(workerInterrupted.getCount()).isEqualTo(1L);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");

            registry.mutationFinished(entry.requestId());
            assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            work.get(1, TimeUnit.SECONDS);
        } finally {
            allowTimeoutTransition.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void completedMutationAtTheTimeoutBoundaryStillCountsAsActivity() throws Exception {
        CountDownLatch timeoutReady = new CountDownLatch(1);
        CountDownLatch allowTimeoutTransition = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch allowWorkerCompletion = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        AiRequestRegistry registry = new AiRequestRegistry(
                scheduler, Duration.ofSeconds(5), AiRequestStateStore.inMemory(), () -> {
            timeoutReady.countDown();
            try {
                allowTimeoutTransition.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        AiRequestRegistry.Entry entry = registry.register(
                "request-fast-mutation", "conversation-fast-mutation", user,
                Instant.now().plusMillis(120));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                workerStarted.countDown();
                try {
                    allowWorkerCompletion.await();
                    registry.finish(entry, null);
                } catch (InterruptedException unexpected) {
                    workerInterrupted.countDown();
                    registry.finish(entry, new CancellationException());
                }
            }, executor);

            assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(timeoutReady.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.mutationStarted(entry.requestId())).isTrue();
            registry.mutationFinished(entry.requestId());
            allowTimeoutTransition.countDown();
            Thread.sleep(40);

            assertThat(workerInterrupted.getCount()).isEqualTo(1L);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");

            allowWorkerCompletion.countDown();
            work.get(1, TimeUnit.SECONDS);
            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("COMPLETED");
        } finally {
            allowTimeoutTransition.countDown();
            allowWorkerCompletion.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void mutationCompletionRenewsBeforeRemovingTheInFlightFence() throws Exception {
        CountDownLatch mutationDecremented = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        AiRequestRegistry registry = new AiRequestRegistry(
                scheduler, Duration.ofSeconds(5), AiRequestStateStore.inMemory(),
                () -> { }, () -> {
            mutationDecremented.countDown();
            try {
                allowCompletion.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        AiRequestRegistry.Entry entry = registry.register(
                "request-finishing-mutation", "conversation-finishing-mutation", user,
                Instant.now().plusMillis(200));
        assertThat(registry.start(entry)).isTrue();
        assertThat(registry.mutationStarted(entry.requestId())).isTrue();
        Thread.sleep(180);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> finish = CompletableFuture.runAsync(
                    () -> registry.mutationFinished(entry.requestId()), executor);

            assertThat(mutationDecremented.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(80);

            assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");
            allowCompletion.countDown();
            finish.get(1, TimeUnit.SECONDS);
            assertThat(registry.finish(entry, null)).isEqualTo("COMPLETED");
        } finally {
            allowCompletion.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void nestedAgentStallFencesTheRequestWithoutSelfInterruptingItsWorker() {
        Thread.interrupted();
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-nested", "conversation-nested", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();

        registry.timeoutExecution("request-nested");

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(registry.shouldDiscardResult("request-nested")).isTrue();
        assertThat(registry.status("request-nested", user).status()).isEqualTo("CANCELLING");
        assertThat(registry.finish(entry, new CancellationException())).isEqualTo("TIMED_OUT");
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
    void anEarlierUserCancellationIsNotRelabeledByTheInactivityReview() throws Exception {
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
    void slowFinalPersistenceCannotBlockOtherRequestInactivityReviews() throws Exception {
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

    @Test
    void instanceShutdownFailsAnOwnedRequestWithoutAObservedMutation() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-shutdown", "conversation-shutdown", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();

        registry.terminalizeOwnedRequestsOnShutdown();

        assertThat(registry.status("request-shutdown", user))
                .extracting(status -> status.status(), status -> status.statusReason())
                .containsExactly("FAILED", "WORKER_INSTANCE_SHUTDOWN");
        assertThat(registry.active(user)).isEmpty();
        assertThat(entry.hasWorkerThread()).isFalse();
    }

    @Test
    void instanceShutdownRequiresReconciliationAfterAnObservedMutation() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-mutating-shutdown", "conversation-mutating-shutdown", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();
        assertThat(registry.mutationStarted(entry.requestId())).isTrue();

        registry.terminalizeOwnedRequestsOnShutdown();

        assertThat(registry.status("request-mutating-shutdown", user))
                .extracting(status -> status.status(), status -> status.statusReason())
                .containsExactly("UNKNOWN_RECONCILIATION_REQUIRED", "WORKER_INSTANCE_SHUTDOWN");
        assertThat(registry.active(user)).isEmpty();
    }

    @Test
    void instanceShutdownDoesNotTerminalizeRequestsOwnedByAnotherInstance() {
        AiRequestStateStore sharedStore = AiRequestStateStore.inMemory();
        var firstScheduler = Executors.newSingleThreadScheduledExecutor();
        var secondScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AiRequestRegistry firstInstance = new AiRequestRegistry(
                    firstScheduler, Duration.ofSeconds(1), sharedStore);
            AiRequestRegistry secondInstance = new AiRequestRegistry(
                    secondScheduler, Duration.ofSeconds(1), sharedStore);
            AiRequestRegistry.Entry first = firstInstance.register(
                    "request-first-instance", "conversation-first-instance", user,
                    Instant.now().plusSeconds(60));
            AiRequestRegistry.Entry second = secondInstance.register(
                    "request-second-instance", "conversation-second-instance", user,
                    Instant.now().plusSeconds(60));
            assertThat(firstInstance.start(first)).isTrue();
            assertThat(secondInstance.start(second)).isTrue();

            firstInstance.terminalizeOwnedRequestsOnShutdown();

            assertThat(firstInstance.status("request-first-instance", user).status())
                    .isEqualTo("FAILED");
            assertThat(secondInstance.status("request-second-instance", user).status())
                    .isEqualTo("RUNNING");
            secondInstance.complete(second);
        } finally {
            firstScheduler.shutdownNow();
            secondScheduler.shutdownNow();
        }
    }

    @Test
    void instanceShutdownInterruptsItsLocalWorker() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-interrupted-shutdown", "conversation-interrupted-shutdown", user,
                Instant.now().plusSeconds(60));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
            assertThat(registry.start(entry)).isTrue();
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.set(true);
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        registry.terminalizeOwnedRequestsOnShutdown();

        worker.get(1, TimeUnit.SECONDS);
        assertThat(interrupted).isTrue();
        assertThat(registry.status("request-interrupted-shutdown", user).status())
                .isEqualTo("FAILED");
    }

    @Test
    void completedCommitLinearizedBeforeShutdownIsNotRelabeled() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiRequestRegistry registry = new AiRequestRegistry(scheduler, Duration.ofSeconds(1));
            AiRequestRegistry.Entry entry = registry.register(
                    "request-commit-shutdown", "conversation-commit-shutdown", user,
                    Instant.now().plusSeconds(60));
            CountDownLatch persistenceStarted = new CountDownLatch(1);
            CountDownLatch releasePersistence = new CountDownLatch(1);
            CompletableFuture<Boolean> commit = CompletableFuture.supplyAsync(() -> {
                assertThat(registry.start(entry)).isTrue();
                return registry.commitResult(entry.requestId(), () -> {
                    persistenceStarted.countDown();
                    try {
                        releasePersistence.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }, executor);
            assertThat(persistenceStarted.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(
                    registry::terminalizeOwnedRequestsOnShutdown, executor);

            releasePersistence.countDown();

            assertThat(commit.get(1, TimeUnit.SECONDS)).isTrue();
            shutdown.get(1, TimeUnit.SECONDS);
            assertThat(registry.status(entry.requestId(), user))
                    .extracting(status -> status.status(), status -> status.statusReason())
                    .containsExactly("COMPLETED", null);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shutdownFenceRejectsLateCommitAndFinishCannotRelabelIt() {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-late-commit", "conversation-late-commit", user,
                Instant.now().plusSeconds(60));
        assertThat(registry.start(entry)).isTrue();

        registry.terminalizeOwnedRequestsOnShutdown();

        assertThat(registry.commitResult(entry.requestId(),
                () -> { throw new AssertionError("late persistence must be fenced"); })).isFalse();
        assertThat(registry.finish(entry, null)).isEqualTo("FAILED");
        assertThat(registry.status(entry.requestId(), user))
                .extracting(status -> status.status(), status -> status.statusReason())
                .containsExactly("FAILED", "WORKER_INSTANCE_SHUTDOWN");
    }

    @Test
    void shutdownDoesNotOverwriteAReplacementOwnerGeneration() {
        AiRequestStateStore sharedStore = AiRequestStateStore.inMemory();
        var scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).factory());
        try {
            AiRequestRegistry registry = new AiRequestRegistry(
                    scheduler, Duration.ofSeconds(1), sharedStore);
            AiRequestRegistry.Entry staleEntry = registry.register(
                    "request-reassigned", "conversation-reassigned", user,
                    Instant.now().plusSeconds(60));
            assertThat(registry.start(staleEntry)).isTrue();
            AiSharedRequestState original = sharedStore.withRequestLock(
                    staleEntry.requestId(), storage -> storage.get(staleEntry.requestId()));
            AiSharedRequestState replacement = new AiSharedRequestState(
                    original.requestId(), original.conversationId(), original.appUserId(),
                    "replacement-instance", original.generation() + 1,
                    original.deadline(), original.expiresAt(), original.createdAt(), Instant.now(),
                    Instant.now(), null, "RUNNING", null, original.terminalTarget(),
                    null, null, null, original.lastEventSequence(), false, 0, false);
            sharedStore.withRequestLock(staleEntry.requestId(), storage -> {
                storage.put(replacement);
                return null;
            });

            registry.terminalizeOwnedRequestsOnShutdown();

            AiSharedRequestState retained = sharedStore.withRequestLock(
                    staleEntry.requestId(), storage -> storage.get(staleEntry.requestId()));
            assertThat(retained)
                    .extracting(AiSharedRequestState::workerInstanceId,
                            AiSharedRequestState::generation, AiSharedRequestState::status)
                    .containsExactly("replacement-instance", replacement.generation(), "RUNNING");
        } finally {
            scheduler.shutdownNow();
        }
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
