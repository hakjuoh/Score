package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class AiChangeApprovalCoordinator {
    private static final int MAX_PENDING_BATCHES = 10_000;
    private static final int MAX_APPROVALS_PER_BATCH = 100;

    private final AiChangeConfirmationService confirmations;
    private final AiRequestRegistry requests;
    private final Duration approvalTimeout;
    private final Consumer<String> batchRegisteredHook;
    private final AiApprovalTrajectoryWriter trajectoryWriter;
    private final Map<String, ParallelGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, PendingBatch> batches = new ConcurrentHashMap<>();
    private final AiApprovalDecisionLedger decisionLedger = new AiApprovalDecisionLedger();
    private final AiApprovalDecisionProcessor decisionProcessor;
    private final AiApprovalWaitLifecycle waitLifecycle;
    private final AiApprovalConfirmationReservations confirmationReservations =
            new AiApprovalConfirmationReservations();

    @Autowired
    public AiChangeApprovalCoordinator(
            AiChangeConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            AiRequestRegistry requests,
            ObjectProvider<ExecutionObserver> executionObservers) {
        this(confirmations, repositoryFactory, properties, requests, ignored -> { },
                executionObservers != null
                        ? executionObservers.getIfAvailable(ExecutionObserver::noop)
                        : ExecutionObserver.noop());
    }

    AiChangeApprovalCoordinator(
            AiChangeConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties) {
        this(confirmations, repositoryFactory, properties, null, ignored -> { },
                ExecutionObserver.noop());
    }

    AiChangeApprovalCoordinator(
            AiChangeConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            Consumer<String> batchRegisteredHook) {
        this(confirmations, repositoryFactory, properties, null, batchRegisteredHook,
                ExecutionObserver.noop());
    }

    AiChangeApprovalCoordinator(
            AiChangeConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            AiRequestRegistry requests,
            Consumer<String> batchRegisteredHook) {
        this(confirmations, repositoryFactory, properties, requests, batchRegisteredHook,
                ExecutionObserver.noop());
    }

    AiChangeApprovalCoordinator(
            AiChangeConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            AiRequestRegistry requests,
            Consumer<String> batchRegisteredHook,
            ExecutionObserver observer) {
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.requests = requests;
        this.approvalTimeout = properties != null
                ? properties.getChangeApprovalTimeout() : Duration.ofMinutes(10);
        this.batchRegisteredHook = Objects.requireNonNull(
                batchRegisteredHook, "batchRegisteredHook");
        this.trajectoryWriter = new AiApprovalTrajectoryWriter(repositoryFactory, observer);
        this.waitLifecycle = new AiApprovalWaitLifecycle(
                approvalTimeout, groups, batches, this::denied);
        this.decisionProcessor = new AiApprovalDecisionProcessor(confirmations,
                trajectoryWriter, decisionLedger, batches,
                waitLifecycle::clearParallelBatch, waitLifecycle::cancelBatch, this::denied);
    }

    public Duration decisionTimeout() {
        return approvalTimeout;
    }

    /**
     * Runs a legacy single-confirmation action only when the confirmation is not
     * owned by an active coordinated wait. The reservation check and action are
     * one critical section so a waiter cannot be registered between them.
     */
    public <T> T whileConfirmationUnreserved(
            String confirmationRequestId, Supplier<T> action) {
        if (!StringUtils.hasText(confirmationRequestId) || action == null) {
            throw new IllegalArgumentException("A change confirmation action is incomplete.");
        }
        return confirmationReservations.whileUnreserved(confirmationRequestId, action);
    }

    /** Opens one approval barrier shared by the supplied parallel participants. */
    public Map<String, AiChangeApprovalScope> openParallelGroup(
            String rootConversationId, String requestId,
            Map<String, Participant> participants) {
        if (!StringUtils.hasText(rootConversationId) || !StringUtils.hasText(requestId)
                || participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("A parallel approval group requires a root request and participants.");
        }
        String groupId = UUID.randomUUID().toString();
        ParallelGroup group = new ParallelGroup(groupId, rootConversationId, requestId,
                immutableLinkedMap(participants));
        if (groups.putIfAbsent(groupId, group) != null) {
            throw new IllegalStateException("Could not reserve a parallel approval group.");
        }
        Map<String, AiChangeApprovalScope> scopes = new LinkedHashMap<>();
        participants.forEach((participantId, participant) -> scopes.put(participantId,
                new AiChangeApprovalScope(rootConversationId, groupId, participantId,
                        participant.agentId(), participant.agentLabel())));
        return immutableLinkedMap(scopes);
    }

    /** Marks a parallel participant terminal so waiting siblings can reach the approval barrier. */
    public void participantFinished(AiChangeApprovalScope scope) {
        if (scope == null || !scope.parallel()) {
            return;
        }
        ParallelGroup group = groups.get(scope.parallelGroupId());
        if (group == null) {
            return;
        }
        PendingBatch ready;
        synchronized (group) {
            if (groups.get(group.id) != group) {
                return;
            }
            group.completed.add(scope.participantId());
            group.waiting.remove(scope.participantId());
            ready = readyBatch(group);
            if (group.completed.containsAll(group.participants.keySet())) {
                groups.remove(group.id, group);
            }
        }
        publish(ready);
    }

    /** Waits for the user's decision while retaining the active agent/tool session. */
    public Map<String, AiChangeApprovalResolution> awaitDecisions(
            ScoreUser requester,
            String requestId,
            String sourceConversationId,
            AiChangeApprovalScope scope,
            List<AiPendingChangeApproval> approvals,
            Consumer<AiChangeApprovalBatchNotice> noticeConsumer) {
        return awaitDecisions(requester, requestId, sourceConversationId, scope, approvals,
                noticeConsumer, ignored -> { }, 0L);
    }

    /** Waits for one decision and emits its committed acknowledgement before resuming work. */
    public Map<String, AiChangeApprovalResolution> awaitDecisions(
            ScoreUser requester,
            String requestId,
            String sourceConversationId,
            AiChangeApprovalScope scope,
            List<AiPendingChangeApproval> approvals,
            Consumer<AiChangeApprovalBatchNotice> noticeConsumer,
            Consumer<DecisionAcknowledgement> acknowledgementConsumer) {
        return awaitDecisions(requester, requestId, sourceConversationId, scope, approvals,
                noticeConsumer, acknowledgementConsumer, 0L);
    }

    public Map<String, AiChangeApprovalResolution> awaitDecisions(
            ScoreUser requester,
            String requestId,
            String sourceConversationId,
            AiChangeApprovalScope scope,
            List<AiPendingChangeApproval> approvals,
            Consumer<AiChangeApprovalBatchNotice> noticeConsumer,
            Consumer<DecisionAcknowledgement> acknowledgementConsumer,
            long generation) {
        if (requester == null || !StringUtils.hasText(requestId)
                || !StringUtils.hasText(sourceConversationId) || scope == null
                || approvals == null || approvals.isEmpty() || noticeConsumer == null
                || acknowledgementConsumer == null) {
            throw new IllegalArgumentException("A pending change approval is incomplete.");
        }
        if (batches.size() >= MAX_PENDING_BATCHES) {
            throw new IllegalStateException("Too many change approvals are pending.");
        }
        Waiter waiter = new Waiter(requester, requestId, Math.max(0L, generation), sourceConversationId,
                scope, List.copyOf(approvals), noticeConsumer,
                acknowledgementConsumer,
                decisionDeadline(), new CompletableFuture<>());
        boolean protectedFromInactivity = requests == null
                || requests.interactionStarted(requestId);
        if (!protectedFromInactivity) {
            return denied(approvals);
        }
        try {
            reserveConfirmations(waiter);
            PendingBatch ready;
            if (scope.parallel()) {
                ParallelGroup group = groups.get(scope.parallelGroupId());
                if (group == null || !group.requestId.equals(requestId)
                        || !group.rootConversationId.equals(scope.rootConversationId())
                        || !group.participants.containsKey(scope.participantId())) {
                    throw new IllegalStateException("The parallel approval group is no longer active.");
                }
                synchronized (group) {
                    if (groups.get(group.id) != group) {
                        throw new IllegalStateException(
                                "The parallel approval group is no longer active.");
                    }
                    if (group.waiting.putIfAbsent(scope.participantId(), waiter) != null) {
                        throw new IllegalStateException(
                                "A parallel participant is already waiting for approval.");
                    }
                    ready = readyBatch(group);
                }
            } else {
                ready = batch(scope.rootConversationId(), requestId, false, List.of(waiter));
            }
            publish(ready);
            try {
                return waiter.resolution.get(
                        waitLifecycle.remainingWaitMillis(waiter), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (waitLifecycle.expire(waiter)) {
                    return denied(approvals);
                }
                throw new IllegalStateException(
                        "The change approval decision was interrupted while committing.", exception);
            } catch (TimeoutException exception) {
                if (waitLifecycle.expire(waiter)) {
                    return denied(approvals);
                }
                return waitLifecycle.awaitCommitting(waiter);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("The change approval could not be completed.",
                        exception.getCause());
            }
        } finally {
            waitLifecycle.removeWaiting(waiter);
            releaseConfirmations(waiter);
            if (requests != null) {
                requests.interactionFinished(requestId);
            }
        }
    }

    /** Applies one complete root-scoped batch decision and releases its exact child sessions. */
    @Transactional
    public DecisionAcknowledgement decide(
            ScoreUser requester, AiChangeApprovalDecisionRequest command) {
        return decisionProcessor.decide(requester, command);
    }

    /** Cancels all approval waits owned by a stopped root request. */
    public void cancelRequest(String requestId) {
        waitLifecycle.cancelRequest(requestId);
    }

    private PendingBatch readyBatch(ParallelGroup group) {
        if (group.activeBatch != null || group.waiting.isEmpty()) {
            return null;
        }
        Set<String> arrived = new LinkedHashSet<>(group.completed);
        arrived.addAll(group.waiting.keySet());
        if (!arrived.containsAll(group.participants.keySet())) {
            return null;
        }
        PendingBatch ready = batch(group.rootConversationId, group.requestId, true,
                new ArrayList<>(group.waiting.values()), group);
        if (ready.state.get() == BatchState.CANCELLED) {
            return null;
        }
        group.activeBatch = ready;
        return ready;
    }

    private PendingBatch batch(String rootConversationId, String requestId,
                               boolean parallel, List<Waiter> waiters) {
        return batch(rootConversationId, requestId, parallel, waiters, null);
    }

    private PendingBatch batch(String rootConversationId, String requestId,
                               boolean parallel, List<Waiter> waiters,
                               ParallelGroup group) {
        String id = UUID.randomUUID().toString();
        Map<String, BatchItem> items = new LinkedHashMap<>();
        waiters.forEach(waiter -> waiter.approvals.forEach(approval -> {
            BatchItem previous = items.put(approval.notice().confirmationRequestId(),
                    new BatchItem(waiter, approval));
            if (previous != null) {
                throw new IllegalStateException(
                        "A change approval identifier cannot appear twice in one batch.");
            }
        }));
        if (items.size() > MAX_APPROVALS_PER_BATCH) {
            throw new IllegalStateException(
                    "A change approval batch cannot contain more than "
                            + MAX_APPROVALS_PER_BATCH + " changes.");
        }
        Instant decisionDeadline = waiters.stream()
                .map(Waiter::waitDeadline)
                .min(Instant::compareTo)
                .orElseThrow();
        Instant expiresAt = decisionDeadline;
        Instant confirmationExpiry = waiters.stream()
                .flatMap(waiter -> waiter.approvals.stream())
                .map(approval -> approval.notice().expiresAt())
                .min(Instant::compareTo)
                .orElse(expiresAt);
        if (confirmationExpiry.isBefore(expiresAt)) {
            expiresAt = confirmationExpiry;
        }
        long generation = waiters.getFirst().generation;
        if (waiters.stream().anyMatch(waiter -> waiter.generation != generation)) {
            throw new IllegalStateException(
                    "A change approval batch cannot span request generations.");
        }
        PendingBatch batch = new PendingBatch(id,
                waiters.getFirst().requester.userId().value().toString(), rootConversationId,
                requestId, generation, parallel, expiresAt, decisionDeadline,
                immutableLinkedMap(items),
                List.copyOf(waiters), group,
                new AtomicReference<>(BatchState.REGISTERED), new AtomicBoolean());
        if (batches.putIfAbsent(batch.id, batch) != null) {
            throw new IllegalStateException("Could not reserve a change approval batch.");
        }
        try {
            batchRegisteredHook.accept(batch.id);
        } catch (RuntimeException failure) {
            waitLifecycle.cancelBatch(batch);
            throw failure;
        }
        return batch;
    }

    private void publish(PendingBatch batch) {
        if (batch == null) {
            return;
        }
        synchronized (batch) {
            if (batch.state.get() != BatchState.REGISTERED
                    || batches.get(batch.id) != batch) {
                return;
            }
            batch.state.set(BatchState.PUBLISHED);
            List<AiChangeApprovalBatchNotice.Item> items = batch.items.values().stream()
                    .map(item -> new AiChangeApprovalBatchNotice.Item(
                            item.approval.notice().confirmationRequestId(),
                            item.approval.toolName(), item.approval.notice().argumentsSummary(),
                            item.waiter.scope.agentId(), item.waiter.scope.agentLabel()))
                    .toList();
            AiChangeApprovalBatchNotice notice = new AiChangeApprovalBatchNotice(
                    batch.id, batch.requestId, batch.rootConversationId,
                    batch.parallel, batch.expiresAt, items);
            try {
                trajectoryWriter.requested(
                        batch.waiters.getFirst().requester(), notice, batch.generation);
                batch.waiters.getFirst().noticeConsumer.accept(notice);
            } catch (RuntimeException failure) {
                waitLifecycle.cancelBatch(batch);
                throw failure;
            }
        }
    }

    private void reserveConfirmations(Waiter waiter) {
        confirmationReservations.reserve(waiter, confirmationIds(waiter));
    }

    private void releaseConfirmations(Waiter waiter) {
        confirmationReservations.release(waiter, confirmationIds(waiter));
    }

    private List<String> confirmationIds(Waiter waiter) {
        return waiter.approvals.stream()
                .map(approval -> approval.notice().confirmationRequestId()).toList();
    }

    private static <K, V> Map<K, V> immutableLinkedMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private Instant decisionDeadline() {
        return Instant.now().plus(approvalTimeout);
    }

    private Map<String, AiChangeApprovalResolution> denied(
            List<AiPendingChangeApproval> approvals) {
        Map<String, AiChangeApprovalResolution> result = new LinkedHashMap<>();
        approvals.forEach(approval -> result.put(approval.notice().confirmationRequestId(),
                new AiChangeApprovalResolution(
                        approval.notice().confirmationRequestId(),
                        AiChangeApprovalResolution.Decision.DENY, null)));
        return Map.copyOf(result);
    }

    public record Participant(String agentId, String agentLabel) {
    }

    public record DecisionAcknowledgement(String batchId, long approved, long denied) {
    }

    static final class ParallelGroup {
        final String id;
        final String rootConversationId;
        final String requestId;
        final Map<String, Participant> participants;
        final Set<String> completed = new LinkedHashSet<>();
        final Map<String, Waiter> waiting = new LinkedHashMap<>();
        PendingBatch activeBatch;

        private ParallelGroup(String id, String rootConversationId, String requestId,
                              Map<String, Participant> participants) {
            this.id = id;
            this.rootConversationId = rootConversationId;
            this.requestId = requestId;
            this.participants = participants;
        }
    }

    record Waiter(
            ScoreUser requester,
            String requestId,
            long generation,
            String sourceConversationId,
            AiChangeApprovalScope scope,
            List<AiPendingChangeApproval> approvals,
            Consumer<AiChangeApprovalBatchNotice> noticeConsumer,
            Consumer<DecisionAcknowledgement> acknowledgementConsumer,
            Instant waitDeadline,
            CompletableFuture<Map<String, AiChangeApprovalResolution>> resolution) {
    }

    record BatchItem(Waiter waiter, AiPendingChangeApproval approval) {
    }

    record PendingBatch(
            String id,
            String appUserId,
            String rootConversationId,
            String requestId,
            long generation,
            boolean parallel,
            Instant expiresAt,
            Instant decisionDeadline,
            Map<String, BatchItem> items,
            List<Waiter> waiters,
            ParallelGroup parallelGroup,
            AtomicReference<BatchState> state,
            AtomicBoolean cancellationRequested) {
    }

    enum BatchState {
        REGISTERED,
        PUBLISHED,
        DECIDING,
        DECIDED,
        CANCELLED
    }
}
