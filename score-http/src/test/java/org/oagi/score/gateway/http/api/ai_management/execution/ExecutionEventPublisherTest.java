package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEventPublisherTest {

    @Test
    void serializesConcurrentWritesAndListenersUsingOneCanonicalIdentity() throws Exception {
        List<Seen> durable = Collections.synchronizedList(new ArrayList<>());
        List<Seen> exported = Collections.synchronizedList(new ArrayList<>());
        List<Seen> realtime = Collections.synchronizedList(new ArrayList<>());
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> exported.add(seen(event))));
        ExecutionScope scope = scope("request-1");
        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        exported.clear();

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 100; index++) {
                executor.submit(() -> publisher.publish(
                        ExecutionObservation.of("agent.run.started", scope, Map.of()),
                        event -> durable.add(seen(event)),
                        event -> realtime.add(seen(event))));
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(durable).hasSize(100).containsExactlyElementsOf(exported);
        assertThat(realtime).containsExactlyElementsOf(exported);
        assertThat(durable).extracting(Seen::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(2, 101)
                        .boxed().toList());
        assertThat(durable).allSatisfy(event -> {
            assertThat(event.id()).isNotBlank();
            assertThat(event.occurredAt()).isNotBlank();
        });
    }

    @Test
    void eachRequestOwnsAnIndependentSequence() {
        List<Long> sequences = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> sequences.add(((Number) event.attributes().get(
                        ExecutionEventPublisher.EVENT_SEQUENCE)).longValue())));
        ExecutionScope scope = scope("request-1");

        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        publisher.observe(ExecutionObservation.of("workflow.root.started",
                new ExecutionScope("request-2", "conversation-1", "user-1", 1,
                        ExecutionScope.Purpose.USER_RESPONSE, List.of()), Map.of()));

        assertThat(sequences).containsExactly(1L, 1L);
    }

    @Test
    void dropsLateEventsForTheClosedGeneration() {
        List<String> types = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> types.add(event.type())));
        ExecutionScope scope = scope("request-1");

        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        types.clear();
        publisher.observe(ExecutionObservation.of(
                ExecutionEventPublisher.REQUEST_CLOSED, scope, Map.of()));
        publisher.observe(ExecutionObservation.of("agent.run.completed", scope, Map.of()));

        assertThat(types).containsExactly(ExecutionEventPublisher.REQUEST_CLOSED);
    }

    @Test
    void neverReopensAClosedGloballyUniqueRequestId() {
        List<String> types = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> types.add(event.type())));
        ExecutionScope scope = scope("request-1");

        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        types.clear();
        publisher.observe(ExecutionObservation.of(
                ExecutionEventPublisher.REQUEST_CLOSED, scope, Map.of()));
        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));

        assertThat(types).containsExactly(ExecutionEventPublisher.REQUEST_CLOSED);
    }

    @Test
    void isolatesAReusedRequestIdByRegistryGeneration() {
        List<String> events = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(event ->
                events.add(event.scope().generation() + ":" + event.type())));
        ExecutionScope first = new ExecutionScope(
                "request-1", "conversation-1", "user-1", 9173,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ExecutionScope second = new ExecutionScope("request-1", "conversation-1", "user-1", 2,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        publisher.observe(ExecutionObservation.of("workflow.root.started", first, Map.of()));
        publisher.observe(ExecutionObservation.of(
                ExecutionEventPublisher.REQUEST_CLOSED, first, Map.of()));
        publisher.observe(ExecutionObservation.of("workflow.root.started", second, Map.of()));
        publisher.observe(ExecutionObservation.of("late.old", first, Map.of()));
        publisher.observe(ExecutionObservation.of("current", second, Map.of()));

        assertThat(events).containsExactly("9173:workflow.root.started", "9173:request.closed",
                "2:workflow.root.started", "2:current");
    }

    @Test
    void aNewGenerationBuffersSetupEvenAfterAnAdmissionRejectionUsedTheSameRequestId() {
        List<String> durable = new ArrayList<>();
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(event ->
                exported.add(event.scope().generation() + ":" + event.type())));
        ExecutionScope rejected = new ExecutionScope(
                "request-1", "conversation-1", "user-1", 0,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ExecutionScope retried = new ExecutionScope(
                "request-1", "conversation-1", "user-1", 23,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        publisher.observe(ExecutionObservation.of("workflow.root.rejected", rejected, Map.of()));
        publisher.observe(ExecutionObservation.of(
                ExecutionEventPublisher.REQUEST_CLOSED, rejected, Map.of()));
        publisher.publish(ExecutionObservation.of(
                "trajectory.settings_change", retried, Map.of()),
                event -> durable.add(event.scope().generation() + ":" + event.type()));
        assertThat(durable).isEmpty();

        publisher.observe(ExecutionObservation.of("workflow.root.started", retried, Map.of()));

        assertThat(durable).containsExactly("23:trajectory.settings_change");
        assertThat(exported).containsExactly(
                "0:workflow.root.rejected", "0:request.closed",
                "23:workflow.root.started", "23:trajectory.settings_change");
    }

    @Test
    void anExplicitGenerationRejectionDiscardsItsSetupAndClosesThatExactStream() {
        List<String> durable = new ArrayList<>();
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(event ->
                exported.add(event.scope().generation() + ":" + event.type())));
        ExecutionScope rejected = new ExecutionScope(
                "request-1", "conversation-1", "user-1", 23,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        publisher.publish(ExecutionObservation.of(
                "trajectory.settings_change", rejected, Map.of()),
                event -> durable.add(event.type()));
        publisher.observe(ExecutionObservation.of("workflow.root.rejected", rejected, Map.of()));
        publisher.observe(ExecutionObservation.of(
                ExecutionEventPublisher.REQUEST_CLOSED, rejected, Map.of()));
        publisher.observe(ExecutionObservation.of("workflow.root.started", rejected, Map.of()));

        assertThat(durable).isEmpty();
        assertThat(exported).containsExactly(
                "23:workflow.root.rejected", "23:request.closed");
    }

    @Test
    void aProductionStyleCompositeDelegatesCanonicalizationBeforeTheDurableWrite() {
        List<ExecutionObservation> durable = new ArrayList<>();
        List<ExecutionObservation> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(exported::add));
        ExecutionObserver productionObserver = ExecutionObserver.composite(List.of(publisher));
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", scope("request-1"), Map.of()));
        exported.clear();

        productionObserver.publish(ExecutionObservation.of(
                "ai.lifecycle", scope("request-1"), Map.of()), durable::add);

        assertThat(durable).singleElement().satisfies(event -> assertThat(event.attributes())
                .containsKeys(ExecutionEventPublisher.EVENT_ID,
                        ExecutionEventPublisher.EVENT_SEQUENCE,
                        ExecutionEventPublisher.EVENT_OCCURRED_AT));
        assertThat(exported).containsExactlyElementsOf(durable);
    }

    @Test
    void rollsBackSequenceAndDoesNotFanOutWhenTheDurableProjectionFails() {
        List<ExecutionObservation> exported = new ArrayList<>();
        List<ExecutionObservation> durable = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(exported::add));
        ExecutionObservation observation = ExecutionObservation.of(
                "ai.lifecycle", scope("request-1"), Map.of());
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", scope("request-1"), Map.of()));
        exported.clear();

        assertThatThrownBy(() -> publisher.publish(observation, ignored -> {
            throw new IllegalStateException("database unavailable");
        })).isInstanceOf(IllegalStateException.class);
        publisher.publish(observation, durable::add);

        assertThat(exported).containsExactlyElementsOf(durable);
        assertThat(durable).singleElement().satisfies(event -> assertThat(event.attributes())
                .containsEntry(ExecutionEventPublisher.EVENT_SEQUENCE, 2L));
    }

    @Test
    void aConcurrentPublisherWaitsUntilItsCausalListenerHasActuallyRun() throws Exception {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(event -> {
            if ("first".equals(event.type())) {
                listenerEntered.countDown();
                try {
                    releaseListener.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", scope("request-1"), Map.of()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> publisher.observe(ExecutionObservation.of(
                    "first", scope("request-1"), Map.of())));
            assertThat(listenerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> publisher.observe(ExecutionObservation.of(
                    "second", scope("request-1"), Map.of())));
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            releaseListener.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(first.isDone()).isTrue();
            assertThat(second.isDone()).isTrue();
        }
    }

    @Test
    void productionDrainCompletesCausalListenersBeforeReturningAndDefersExternalIo()
            throws Exception {
        List<String> causal = new ArrayList<>();
        List<String> external = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch externalEntered = new CountDownLatch(1);
        CountDownLatch releaseExternal = new CountDownLatch(1);
        CountDownLatch externalCompleted = new CountDownLatch(2);
        ExecutionEventListener externalListener = new ExecutionEventListener() {
            @Override
            public void onEvent(ExecutionObservation event) {
                if ("workflow.root.started".equals(event.type())) return;
                externalEntered.countDown();
                try {
                    releaseExternal.await(2, TimeUnit.SECONDS);
                    external.add(event.type());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    externalCompleted.countDown();
                }
            }

            @Override
            public boolean causal() {
                return false;
            }
        };
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> {
                    if (!"workflow.root.started".equals(event.type())) causal.add(event.type());
                }, externalListener), true);
        publisher.observe(ExecutionObservation.of("workflow.root.started",
                scope("request-1"), Map.of()));

        publisher.observe(ExecutionObservation.of("agent.run.started",
                scope("request-1"), Map.of()));
        assertThat(causal).containsExactly("agent.run.started");
        assertThat(externalEntered.await(1, TimeUnit.SECONDS)).isTrue();

        publisher.observe(ExecutionObservation.of("agent.run.completed",
                scope("request-1"), Map.of()));
        assertThat(causal).containsExactly("agent.run.started", "agent.run.completed");
        assertThat(external).isEmpty();

        releaseExternal.countDown();
        assertThat(externalCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(external).containsExactly("agent.run.started", "agent.run.completed");
        publisher.closeExternalDrains();
    }

    @Test
    void canonicalizesLegacyGenerationZeroEventsToTheActiveRequestGeneration() {
        List<Long> generations = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> generations.add(event.scope().generation())));
        ExecutionScope active = new ExecutionScope("request-1", "conversation-1", "user-1", 9173,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ExecutionScope legacy = new ExecutionScope("request-1", "conversation-1", "user-1", 0,
                ExecutionScope.Purpose.GUARDRAIL_EVALUATION, List.of());

        publisher.observe(ExecutionObservation.of("workflow.root.started", active, Map.of()));
        publisher.observe(ExecutionObservation.of("trajectory.approval", legacy, Map.of()));

        assertThat(generations).containsExactly(9173L, 9173L);
    }

    @Test
    void publishesTransactionBoundEventsOnlyAfterCommitAndNeverAfterRollback() {
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> exported.add(event.type())));
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", scope("request-1"), Map.of()));
        exported.clear();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(ExecutionObservation.of("committed", scope("request-1"), Map.of()),
                    ignored -> { });
            assertThat(exported).isEmpty();
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
            assertThat(exported).containsExactly("committed");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(ExecutionObservation.of("rolled-back", scope("request-1"), Map.of()),
                    ignored -> { });
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(exported).containsExactly("committed");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void terminalPublicationFromTransactionCompletionIsDispatchedImmediately() {
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(event ->
                exported.add(event.type())));
        ExecutionScope scope = scope("request-after-completion");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
            List<TransactionSynchronization> rootSynchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            rootSynchronizations.forEach(TransactionSynchronization::afterCommit);

            int beforeTerminal = TransactionSynchronizationManager.getSynchronizations().size();
            ExecutionEventPublisher.runAfterTransactionCompletion(() -> publisher.observe(
                    ExecutionObservation.of(
                            ExecutionEventPublisher.REQUEST_CLOSED, scope, Map.of())));

            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .hasSize(beforeTerminal);
            assertThat(exported).containsExactly(
                    "workflow.root.started", ExecutionEventPublisher.REQUEST_CLOSED);
            rootSynchronizations.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void aLaterTerminalPublishWaitsForAnEarlierTransactionCommitToFillTheSequenceGap()
            throws Exception {
        List<String> exported = Collections.synchronizedList(new ArrayList<>());
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> exported.add(event.type())));
        ExecutionScope scope = scope("request-gap");
        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        exported.clear();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        List<TransactionSynchronization> synchronizations;
        try {
            publisher.publish(ExecutionObservation.of("first", scope, Map.of()), ignored -> { });
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }

        CountDownLatch terminalDurable = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var terminal = executor.submit(() -> publisher.publish(ExecutionObservation.of(
                    ExecutionEventPublisher.REQUEST_CLOSED, scope, Map.of()),
                    ignored -> terminalDurable.countDown()));
            assertThat(terminalDurable.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(terminal.isDone()).isFalse();

            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));
            assertThat(terminal.get(2, TimeUnit.SECONDS)).isNull();
            executor.shutdown();
        }

        assertThat(exported).containsExactly("first", ExecutionEventPublisher.REQUEST_CLOSED);
    }

    @Test
    void externalExecutorRejectionDuringShutdownNeverEscapesToThePublisher() {
        ExecutionEventListener external = new ExecutionEventListener() {
            @Override
            public void onEvent(ExecutionObservation event) { }

            @Override
            public boolean causal() { return false; }
        };
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(external), true);
        ExecutionScope scope = scope("request-shutdown");
        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        publisher.closeExternalDrains();

        assertThatCode(() -> publisher.observe(
                ExecutionObservation.of("agent.run.started", scope, Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void aFullExternalFifoAppliesBackpressureWithoutDroppingOrReorderingEvents()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4098);
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        ExecutionEventListener external = new ExecutionEventListener() {
            @Override
            public void onEvent(ExecutionObservation event) {
                if (firstEntered.getCount() > 0) {
                    firstEntered.countDown();
                    try {
                        releaseFirst.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                sequences.add(((Number) event.attributes().get(
                        ExecutionEventPublisher.EVENT_SEQUENCE)).longValue());
                completed.countDown();
            }

            @Override
            public boolean causal() { return false; }
        };
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(external), true);
        ExecutionScope scope = scope("request-backpressure");
        publisher.observe(ExecutionObservation.of("workflow.root.started", scope, Map.of()));
        assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

        try (var executor = Executors.newSingleThreadExecutor()) {
            for (int index = 0; index < 4096; index++) {
                publisher.observe(ExecutionObservation.of("queued", scope, Map.of()));
            }
            var overflow = executor.submit(() -> publisher.observe(
                    ExecutionObservation.of("overflow", scope, Map.of())));
            assertThat(overflow.isDone()).isFalse();

            releaseFirst.countDown();
            overflow.get(5, TimeUnit.SECONDS);
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            publisher.closeExternalDrains();
        }

        assertThat(sequences).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 4098).boxed().toList());
    }

    @Test
    void buffersSetupProjectionUntilTheRootWorkflowStarts() {
        List<String> durable = new ArrayList<>();
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> exported.add(event.type())));

        publisher.publish(ExecutionObservation.of("trajectory.settings_change",
                scope("request-1"), Map.of()), event -> durable.add(event.type()));
        assertThat(durable).isEmpty();
        publisher.observe(ExecutionObservation.of("workflow.root.started",
                scope("request-1"), Map.of()));

        assertThat(durable).containsExactly("trajectory.settings_change");
        assertThat(exported).containsExactly(
                "workflow.root.started", "trajectory.settings_change");
    }

    @Test
    void terminalAllocationDropsConcurrentLateEventsWhenCommitSucceeds() {
        List<String> durable = new ArrayList<>();
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> exported.add(event.type())));
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", scope("request-1"), Map.of()));
        exported.clear();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(ExecutionObservation.of(ExecutionEventPublisher.REQUEST_CLOSED,
                    scope("request-1"), Map.of()), event -> durable.add(event.type()));
            publisher.publish(ExecutionObservation.of("late", scope("request-1"), Map.of()),
                    event -> durable.add(event.type()));
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(durable).containsExactly(ExecutionEventPublisher.REQUEST_CLOSED);
        assertThat(exported).containsExactly(ExecutionEventPublisher.REQUEST_CLOSED);
    }

    @Test
    void terminalRollbackReplaysEventsThatArrivedWhileClosing() {
        List<String> durable = new ArrayList<>();
        List<String> exported = new ArrayList<>();
        ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(
                event -> exported.add(event.type())));
        publisher.observe(ExecutionObservation.of(
                "workflow.root.started", scope("request-1"), Map.of()));
        exported.clear();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(ExecutionObservation.of(ExecutionEventPublisher.REQUEST_CLOSED,
                    scope("request-1"), Map.of()), ignored -> { });
            publisher.publish(ExecutionObservation.of("replayed", scope("request-1"), Map.of()),
                    event -> durable.add(event.type()));
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(durable).containsExactly("replayed");
        assertThat(exported).containsExactly("replayed");
    }

    private Seen seen(ExecutionObservation event) {
        return new Seen(event.attributes().get(ExecutionEventPublisher.EVENT_ID).toString(),
                ((Number) event.attributes().get(ExecutionEventPublisher.EVENT_SEQUENCE)).longValue(),
                event.attributes().get(ExecutionEventPublisher.EVENT_OCCURRED_AT).toString());
    }

    private ExecutionScope scope(String requestId) {
        return new ExecutionScope(requestId, "conversation-1", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
    }

    private record Seen(String id, long sequence, String occurredAt) { }
}
