package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationDecision;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

/**
 * Coordinates approval barriers without replaying a model turn. Individual agent
 * calls wait independently; participants in a parallel group are released by one
 * root-scoped batch decision after every sibling has either finished or reached
 * the same barrier.
 */
@Component
public class AiMutationApprovalCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiMutationApprovalCoordinator.class);
    private static final int MAX_PENDING_BATCHES = 10_000;
    private static final int MAX_DECIDED_BATCHES = 10_000;
    private static final int MAX_APPROVALS_PER_BATCH = 100;
    private static final Duration DECIDED_BATCH_RETENTION = Duration.ofMinutes(2);

    private final AiMutationConfirmationService confirmations;
    private final RepositoryFactory repositoryFactory;
    private final AiRequestRegistry requests;
    private final Duration requestTimeout;
    private final Consumer<String> batchRegisteredHook;
    private final Map<String, ParallelGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, PendingBatch> batches = new ConcurrentHashMap<>();
    private final Map<String, DecidedBatch> decidedBatches = new ConcurrentHashMap<>();
    private final Object confirmationReservationMonitor = new Object();
    private final Map<String, Waiter> confirmationReservations = new LinkedHashMap<>();

    @Autowired
    public AiMutationApprovalCoordinator(
            AiMutationConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            AiRequestRegistry requests) {
        this(confirmations, repositoryFactory, properties, requests, ignored -> { });
    }

    AiMutationApprovalCoordinator(
            AiMutationConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties) {
        this(confirmations, repositoryFactory, properties, null, ignored -> { });
    }

    AiMutationApprovalCoordinator(
            AiMutationConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            Consumer<String> batchRegisteredHook) {
        this(confirmations, repositoryFactory, properties, null, batchRegisteredHook);
    }

    AiMutationApprovalCoordinator(
            AiMutationConfirmationService confirmations,
            RepositoryFactory repositoryFactory,
            ScoreAiProperties properties,
            AiRequestRegistry requests,
            Consumer<String> batchRegisteredHook) {
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory, "repositoryFactory");
        this.requests = requests;
        this.requestTimeout = properties != null
                ? properties.getRequestTimeout() : Duration.ofMinutes(10);
        this.batchRegisteredHook = Objects.requireNonNull(
                batchRegisteredHook, "batchRegisteredHook");
    }

    public Duration decisionTimeout() {
        return requestTimeout;
    }

    /**
     * Runs a legacy single-confirmation action only when the confirmation is not
     * owned by an active coordinated wait. The reservation check and action are
     * one critical section so a waiter cannot be registered between them.
     */
    public <T> T whileConfirmationUnreserved(
            String confirmationRequestId, Supplier<T> action) {
        if (!StringUtils.hasText(confirmationRequestId) || action == null) {
            throw new IllegalArgumentException("A mutation confirmation action is incomplete.");
        }
        synchronized (confirmationReservationMonitor) {
            if (confirmationReservations.containsKey(confirmationRequestId)) {
                throw new IllegalStateException(
                        "This mutation confirmation belongs to an active approval request.");
            }
            return action.get();
        }
    }

    /** Opens one approval barrier shared by the supplied parallel participants. */
    public Map<String, AiMutationApprovalScope> openParallelGroup(
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
        Map<String, AiMutationApprovalScope> scopes = new LinkedHashMap<>();
        participants.forEach((participantId, participant) -> scopes.put(participantId,
                new AiMutationApprovalScope(rootConversationId, groupId, participantId,
                        participant.agentId(), participant.agentLabel())));
        return immutableLinkedMap(scopes);
    }

    /** Marks a parallel participant terminal so waiting siblings can reach the approval barrier. */
    public void participantFinished(AiMutationApprovalScope scope) {
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
    public Map<String, AiMutationApprovalResolution> awaitDecisions(
            ScoreUser requester,
            String requestId,
            String sourceConversationId,
            AiMutationApprovalScope scope,
            List<AiPendingMutationApproval> approvals,
            Consumer<AiMutationApprovalBatchNotice> noticeConsumer) {
        return awaitDecisions(requester, requestId, sourceConversationId, scope, approvals,
                noticeConsumer, ignored -> { });
    }

    /** Waits for one decision and emits its committed acknowledgement before resuming work. */
    public Map<String, AiMutationApprovalResolution> awaitDecisions(
            ScoreUser requester,
            String requestId,
            String sourceConversationId,
            AiMutationApprovalScope scope,
            List<AiPendingMutationApproval> approvals,
            Consumer<AiMutationApprovalBatchNotice> noticeConsumer,
            Consumer<DecisionAcknowledgement> acknowledgementConsumer) {
        if (requester == null || !StringUtils.hasText(requestId)
                || !StringUtils.hasText(sourceConversationId) || scope == null
                || approvals == null || approvals.isEmpty() || noticeConsumer == null
                || acknowledgementConsumer == null) {
            throw new IllegalArgumentException("A pending mutation approval is incomplete.");
        }
        if (batches.size() >= MAX_PENDING_BATCHES) {
            throw new IllegalStateException("Too many mutation approvals are pending.");
        }
        Waiter waiter = new Waiter(requester, requestId, sourceConversationId,
                scope, List.copyOf(approvals), noticeConsumer,
                acknowledgementConsumer,
                decisionDeadline(requester, requestId), new CompletableFuture<>());
        reserveConfirmations(waiter);
        try {
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
                        remainingWaitMillis(waiter), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (expireWaiter(waiter)) {
                    return denied(approvals);
                }
                throw new IllegalStateException(
                        "The mutation approval decision was interrupted while committing.", exception);
            } catch (TimeoutException exception) {
                if (expireWaiter(waiter)) {
                    return denied(approvals);
                }
                return awaitCommittingDecision(waiter);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("The mutation approval could not be completed.",
                        exception.getCause());
            }
        } finally {
            removeWaiting(waiter);
            releaseConfirmations(waiter);
        }
    }

    /** Applies one complete root-scoped batch decision and releases its exact child sessions. */
    @Transactional
    public DecisionAcknowledgement decide(
            ScoreUser requester, AiMutationApprovalDecisionRequest command) {
        if (requester == null || command == null || !StringUtils.hasText(command.batchId())
                || !StringUtils.hasText(command.requestId())
                || !StringUtils.hasText(command.conversationId())) {
            throw new IllegalArgumentException("A mutation approval decision is incomplete.");
        }
        Map<String, String> requested = decisions(command.decisions());
        if (decidedBatches.containsKey(command.batchId())) {
            return replayDecision(requester, command, requested);
        }
        PendingBatch batch = batches.get(command.batchId());
        if (batch == null) {
            return replayDecision(requester, command, requested);
        }
        if (!batch.appUserId.equals(requester.userId().value().toString())) {
            throw new AccessDeniedException("The mutation approval batch belongs to another user.");
        }
        if (!batch.requestId.equals(command.requestId())
                || !batch.rootConversationId.equals(command.conversationId())) {
            throw new IllegalArgumentException("The mutation approval batch identity does not match.");
        }
        Set<String> expected = new LinkedHashSet<>(batch.items.keySet());
        if (!requested.keySet().equals(expected)) {
            throw new IllegalArgumentException("Every mutation approval item requires exactly one decision.");
        }

        synchronized (batch) {
            if (batches.get(batch.id) != batch) {
                if (decidedBatches.containsKey(command.batchId())) {
                    return replayDecision(requester, command, requested);
                }
                throw new IllegalArgumentException("The mutation approval batch was already answered.");
            }
            if (batch.state.get() != BatchState.PUBLISHED) {
                if (decidedBatches.containsKey(command.batchId())) {
                    return replayDecision(requester, command, requested);
                }
                throw new IllegalArgumentException("The mutation approval batch is not awaiting a decision.");
            }
            if (!Instant.now().isBefore(batch.expiresAt)) {
                cancelBatch(batch);
                throw new IllegalArgumentException("The mutation approval batch has expired.");
            }
            batch.state.set(BatchState.DECIDING);
        }
        try {
            registerDecisionDeadlineFence(batch);
            List<BatchItem> orderedItems = List.copyOf(batch.items.values());
            List<AiMutationDecision> applied = confirmations.decideBatch(requester,
                    orderedItems.stream().map(item ->
                            new AiMutationConfirmationService.BatchDecision(
                                    item.waiter.sourceConversationId,
                                    item.approval.notice().confirmationRequestId(),
                                    requested.get(item.approval.notice().confirmationRequestId())))
                            .toList());
            if (!Instant.now().isBefore(batch.decisionDeadline)) {
                throw new IllegalStateException(
                        "The mutation approval decision exceeded the active request deadline.");
            }
            Map<Waiter, Map<String, AiMutationApprovalResolution>> byWaiter =
                    new LinkedHashMap<>();
            for (int index = 0; index < orderedItems.size(); index++) {
                BatchItem item = orderedItems.get(index);
                String requestedDecision = requested.get(
                        item.approval.notice().confirmationRequestId());
                String grant = applied.get(index).response().confirmationGrant();
                AiMutationApprovalResolution resolution = new AiMutationApprovalResolution(
                        item.approval.notice().confirmationRequestId(),
                        "APPROVE".equals(requestedDecision)
                                ? AiMutationApprovalResolution.Decision.APPROVE
                                : AiMutationApprovalResolution.Decision.DENY,
                        grant);
                if (resolution.decision() == AiMutationApprovalResolution.Decision.APPROVE
                        && !resolution.approved()) {
                    throw new IllegalStateException(
                            "An approved mutation did not produce a one-time grant.");
                }
                byWaiter.computeIfAbsent(item.waiter, ignored -> new LinkedHashMap<>())
                        .put(resolution.confirmationRequestId(), resolution);
            }

            recordDecision(requester, batch, requested);
            long approved = requested.values().stream().filter("APPROVE"::equals).count();
            DecisionAcknowledgement acknowledgement = new DecisionAcknowledgement(
                    batch.id, approved, requested.size() - approved);
            completeAfterCommit(batch, byWaiter, requested, acknowledgement, () ->
                    batch.waiters.getFirst().acknowledgementConsumer.accept(acknowledgement));
            return null;
        } catch (RuntimeException failure) {
            rollbackDecision(batch);
            throw failure;
        }
    }

    private void completeAfterCommit(
            PendingBatch batch,
            Map<Waiter, Map<String, AiMutationApprovalResolution>> byWaiter,
            Map<String, String> decisions,
            DecisionAcknowledgement acknowledgement,
            Runnable committedAcknowledgement) {
        Runnable completion = () -> completeDecision(batch, byWaiter);
        Runnable acknowledgeThenComplete = () -> {
            rememberDecision(batch, decisions, acknowledgement);
            try {
                committedAcknowledgement.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not publish a committed mutation approval acknowledgement", failure);
            } finally {
                completion.run();
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            acknowledgeThenComplete.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        acknowledgeThenComplete.run();
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            rollbackDecision(batch);
                        }
                    }
                });
    }

    private DecisionAcknowledgement replayDecision(
            ScoreUser requester,
            AiMutationApprovalDecisionRequest command,
            Map<String, String> requested) {
        Instant now = Instant.now();
        purgeExpiredDecisions(now);
        DecidedBatch decided = decidedBatches.get(command.batchId());
        if (decided == null || !now.isBefore(decided.expiresAt)) {
            if (decided != null) {
                decidedBatches.remove(command.batchId(), decided);
            }
            throw new IllegalArgumentException("The mutation approval batch is no longer pending.");
        }
        if (!decided.appUserId.equals(requester.userId().value().toString())) {
            throw new AccessDeniedException("The mutation approval batch belongs to another user.");
        }
        if (!decided.requestId.equals(command.requestId())
                || !decided.rootConversationId.equals(command.conversationId())) {
            throw new IllegalArgumentException("The mutation approval batch identity does not match.");
        }
        if (!decided.decisions.equals(requested)) {
            throw new IllegalArgumentException(
                    "The mutation approval batch was already answered with different decisions.");
        }
        return decided.acknowledgement;
    }

    private void rememberDecision(
            PendingBatch batch,
            Map<String, String> decisions,
            DecisionAcknowledgement acknowledgement) {
        Instant now = Instant.now();
        purgeExpiredDecisions(now);
        if (decidedBatches.size() >= MAX_DECIDED_BATCHES) {
            decidedBatches.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparing(DecidedBatch::expiresAt)))
                    .ifPresent(entry -> decidedBatches.remove(entry.getKey(), entry.getValue()));
        }
        decidedBatches.put(batch.id, new DecidedBatch(
                batch.appUserId, batch.rootConversationId, batch.requestId,
                Map.copyOf(decisions), acknowledgement,
                now.plus(DECIDED_BATCH_RETENTION)));
    }

    private void purgeExpiredDecisions(Instant now) {
        decidedBatches.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    }

    private void registerDecisionDeadlineFence(PendingBatch batch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        if (!Instant.now().isBefore(batch.decisionDeadline)) {
                            throw new IllegalStateException(
                                    "The mutation approval decision exceeded the active request deadline.");
                        }
                    }
                });
    }

    private void completeDecision(
            PendingBatch batch,
            Map<Waiter, Map<String, AiMutationApprovalResolution>> byWaiter) {
        synchronized (batch) {
            if (batch.state.get() != BatchState.DECIDING
                    || !batches.remove(batch.id, batch)) {
                return;
            }
            batch.state.set(BatchState.DECIDED);
        }
        clearParallelBatch(batch);
        byWaiter.forEach((waiter, resolutions) ->
                waiter.resolution.complete(immutableLinkedMap(resolutions)));
    }

    private void rollbackDecision(PendingBatch batch) {
        boolean cancel;
        synchronized (batch) {
            if (batch.state.get() != BatchState.DECIDING) {
                return;
            }
            cancel = batch.cancellationRequested.get()
                    || !Instant.now().isBefore(batch.decisionDeadline);
            batch.state.set(cancel ? BatchState.CANCELLED : BatchState.PUBLISHED);
            if (cancel) {
                batches.remove(batch.id, batch);
            }
        }
        if (cancel) {
            clearParallelBatch(batch);
            batch.waiters.forEach(waiter ->
                    waiter.resolution.complete(denied(waiter.approvals)));
        }
    }

    /** Cancels all approval waits owned by a stopped root request. */
    public void cancelRequest(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return;
        }
        batches.values().stream()
                .filter(batch -> requestId.equals(batch.requestId))
                .toList()
                .forEach(batch -> cancelBatch(batch));
        groups.values().stream()
                .filter(group -> requestId.equals(group.requestId))
                .toList()
                .forEach(this::cancelGroup);
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
                        "A mutation approval identifier cannot appear twice in one batch.");
            }
        }));
        if (items.size() > MAX_APPROVALS_PER_BATCH) {
            throw new IllegalStateException(
                    "A mutation approval batch cannot contain more than "
                            + MAX_APPROVALS_PER_BATCH + " actions.");
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
        PendingBatch batch = new PendingBatch(id,
                waiters.getFirst().requester.userId().value().toString(), rootConversationId,
                requestId, parallel, expiresAt, decisionDeadline, immutableLinkedMap(items),
                List.copyOf(waiters), group,
                new AtomicReference<>(BatchState.REGISTERED), new AtomicBoolean());
        if (batches.putIfAbsent(batch.id, batch) != null) {
            throw new IllegalStateException("Could not reserve a mutation approval batch.");
        }
        try {
            batchRegisteredHook.accept(batch.id);
        } catch (RuntimeException failure) {
            cancelBatch(batch);
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
            List<AiMutationApprovalBatchNotice.Item> items = batch.items.values().stream()
                    .map(item -> new AiMutationApprovalBatchNotice.Item(
                            item.approval.notice().confirmationRequestId(),
                            item.approval.toolName(), item.approval.notice().argumentsSummary(),
                            item.waiter.scope.agentId(), item.waiter.scope.agentLabel()))
                    .toList();
            AiMutationApprovalBatchNotice notice = new AiMutationApprovalBatchNotice(
                    batch.id, batch.requestId, batch.rootConversationId,
                    batch.parallel, batch.expiresAt, items);
            try {
                recordRequested(batch.waiters.getFirst().requester(), notice);
                batch.waiters.getFirst().noticeConsumer.accept(notice);
            } catch (RuntimeException failure) {
                cancelBatch(batch);
                throw failure;
            }
        }
    }

    private Map<String, String> decisions(
            List<AiMutationApprovalDecisionRequest.ItemDecision> decisions) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AiMutationApprovalDecisionRequest.ItemDecision item : decisions) {
            String decision = StringUtils.hasText(item.decision())
                    ? item.decision().strip().toUpperCase() : "";
            if (!StringUtils.hasText(item.confirmationRequestId())
                    || (!"APPROVE".equals(decision) && !"DENY".equals(decision))
                    || result.putIfAbsent(item.confirmationRequestId(), decision) != null) {
                throw new IllegalArgumentException("Mutation approval decisions must be unique APPROVE or DENY values.");
            }
        }
        return Map.copyOf(result);
    }

    private void reserveConfirmations(Waiter waiter) {
        synchronized (confirmationReservationMonitor) {
            for (AiPendingMutationApproval approval : waiter.approvals) {
                String confirmationRequestId = approval.notice().confirmationRequestId();
                if (!StringUtils.hasText(confirmationRequestId)
                        || confirmationReservations.containsKey(confirmationRequestId)) {
                    throw new IllegalStateException(
                            "A mutation confirmation is already owned by an active approval request.");
                }
            }
            waiter.approvals.forEach(approval -> confirmationReservations.put(
                    approval.notice().confirmationRequestId(), waiter));
        }
    }

    private void releaseConfirmations(Waiter waiter) {
        synchronized (confirmationReservationMonitor) {
            waiter.approvals.forEach(approval -> confirmationReservations.remove(
                    approval.notice().confirmationRequestId(), waiter));
        }
    }

    private static <K, V> Map<K, V> immutableLinkedMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private boolean expireWaiter(Waiter waiter) {
        PendingBatch batch = batches.values().stream()
                .filter(candidate -> candidate.waiters.contains(waiter))
                .findFirst()
                .orElse(null);
        if (batch != null) {
            return cancelBatch(batch);
        }
        removeWaiting(waiter);
        waiter.resolution.complete(denied(waiter.approvals));
        return true;
    }

    private Map<String, AiMutationApprovalResolution> awaitCommittingDecision(Waiter waiter) {
        try {
            return waiter.resolution.get(
                    Math.max(1L, requestTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "The mutation approval decision was interrupted while committing.", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "The mutation approval decision outcome is unknown after its transaction deadline.",
                    exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("The mutation approval could not be completed.",
                    exception.getCause());
        }
    }

    private long remainingWaitMillis(Waiter waiter) {
        Instant expiresAt = waiter.approvals.stream()
                .map(approval -> approval.notice().expiresAt())
                .min(Instant::compareTo)
                .filter(expiration -> expiration.isBefore(waiter.waitDeadline))
                .orElse(waiter.waitDeadline);
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return 1L;
        }
        return Math.max(1L, remaining.toMillis() + 1L);
    }

    private Instant decisionDeadline(ScoreUser requester, String requestId) {
        Instant fallback = Instant.now().plus(requestTimeout);
        if (requests == null) {
            return fallback;
        }
        Instant requestDeadline = requests.status(requestId, requester).deadline();
        return requestDeadline != null && requestDeadline.isBefore(fallback)
                ? requestDeadline : fallback;
    }

    private void removeWaiting(Waiter waiter) {
        if (!waiter.scope.parallel()) {
            return;
        }
        ParallelGroup group = groups.get(waiter.scope.parallelGroupId());
        if (group == null) {
            return;
        }
        synchronized (group) {
            group.waiting.remove(waiter.scope.participantId(), waiter);
        }
    }

    private void cancelGroup(ParallelGroup group) {
        if (!groups.remove(group.id, group)) {
            return;
        }
        List<Waiter> waiting;
        PendingBatch activeBatch;
        synchronized (group) {
            waiting = List.copyOf(group.waiting.values());
            activeBatch = group.activeBatch;
            group.waiting.clear();
            group.activeBatch = null;
        }
        boolean activeCancelled = activeBatch == null || cancelBatch(activeBatch);
        waiting.forEach(waiter -> {
            if (activeCancelled || activeBatch == null || !activeBatch.waiters.contains(waiter)) {
                waiter.resolution.complete(denied(waiter.approvals));
            }
        });
    }

    private boolean cancelBatch(PendingBatch batch) {
        synchronized (batch) {
            BatchState state = batch.state.get();
            if (state == BatchState.DECIDING) {
                batch.cancellationRequested.set(true);
                return false;
            }
            if (state == BatchState.DECIDED || state == BatchState.CANCELLED
                    || !batches.remove(batch.id, batch)) {
                return state == BatchState.CANCELLED;
            }
            batch.state.set(BatchState.CANCELLED);
        }
        clearParallelBatch(batch);
        batch.waiters.forEach(waiter -> waiter.resolution.complete(denied(waiter.approvals)));
        return true;
    }

    private void clearParallelBatch(PendingBatch batch) {
        if (batch.parallelGroup != null) {
            synchronized (batch.parallelGroup) {
                if (batch.parallelGroup.activeBatch == batch) {
                    batch.parallelGroup.activeBatch = null;
                }
                batch.waiters.forEach(waiter -> batch.parallelGroup.waiting.remove(
                        waiter.scope.participantId(), waiter));
            }
        }
    }

    private Map<String, AiMutationApprovalResolution> denied(
            List<AiPendingMutationApproval> approvals) {
        Map<String, AiMutationApprovalResolution> result = new LinkedHashMap<>();
        approvals.forEach(approval -> result.put(approval.notice().confirmationRequestId(),
                new AiMutationApprovalResolution(
                        approval.notice().confirmationRequestId(),
                        AiMutationApprovalResolution.Decision.DENY, null)));
        return Map.copyOf(result);
    }

    private void recordRequested(ScoreUser requester, AiMutationApprovalBatchNotice notice) {
        List<Map<String, Object>> items = notice.items().stream().map(item -> Map.<String, Object>of(
                "confirmationRequestId", item.confirmationRequestId(),
                "toolName", item.toolName(),
                "argumentsSummary", item.argumentsSummary(),
                "agentId", Objects.toString(item.agentId(), ""),
                "agentLabel", Objects.toString(item.agentLabel(), ""))).toList();
        append(requester, notice.rootConversationId(), new AiChatTrajectoryStep(
                notice.requestId(), "system", "mutation_approval_batch_requested", "visible",
                notice.items().size() == 1
                        ? "Approval requested for one data-changing action."
                        : "Approval requested for " + notice.items().size() + " data-changing actions.",
                null, null, null, null, null, null,
                Map.of("batchId", notice.batchId(), "parallel", notice.parallel(),
                        "expiresAt", notice.expiresAt().toString(), "items", items),
                0, null, Instant.now()));
    }

    private void recordDecision(ScoreUser requester, PendingBatch batch, Map<String, String> decisions) {
        long approved = decisions.values().stream().filter("APPROVE"::equals).count();
        long denied = decisions.size() - approved;
        append(requester, batch.rootConversationId, new AiChatTrajectoryStep(
                batch.requestId, "user", "mutation_approval_decision", "visible",
                "Approved " + approved + " action" + (approved == 1 ? "" : "s")
                        + " and denied " + denied + ".",
                null, null, null, null, null, null,
                Map.of("batchId", batch.id, "decisions", decisions),
                0, null, Instant.now()));
    }

    private void append(ScoreUser requester, String conversationId, AiChatTrajectoryStep step) {
        AiChatConversationRepository repository =
                repositoryFactory.aiChatConversationRepository(
                        requester, AiChatJsonSerializer.getInstance());
        repository.append(conversationId, step);
    }

    public record Participant(String agentId, String agentLabel) {
    }

    public record DecisionAcknowledgement(String batchId, long approved, long denied) {
    }

    private static final class ParallelGroup {
        private final String id;
        private final String rootConversationId;
        private final String requestId;
        private final Map<String, Participant> participants;
        private final Set<String> completed = new LinkedHashSet<>();
        private final Map<String, Waiter> waiting = new LinkedHashMap<>();
        private PendingBatch activeBatch;

        private ParallelGroup(String id, String rootConversationId, String requestId,
                              Map<String, Participant> participants) {
            this.id = id;
            this.rootConversationId = rootConversationId;
            this.requestId = requestId;
            this.participants = participants;
        }
    }

    private record Waiter(
            ScoreUser requester,
            String requestId,
            String sourceConversationId,
            AiMutationApprovalScope scope,
            List<AiPendingMutationApproval> approvals,
            Consumer<AiMutationApprovalBatchNotice> noticeConsumer,
            Consumer<DecisionAcknowledgement> acknowledgementConsumer,
            Instant waitDeadline,
            CompletableFuture<Map<String, AiMutationApprovalResolution>> resolution) {
    }

    private record BatchItem(Waiter waiter, AiPendingMutationApproval approval) {
    }

    private record PendingBatch(
            String id,
            String appUserId,
            String rootConversationId,
            String requestId,
            boolean parallel,
            Instant expiresAt,
            Instant decisionDeadline,
            Map<String, BatchItem> items,
            List<Waiter> waiters,
            ParallelGroup parallelGroup,
            AtomicReference<BatchState> state,
            AtomicBoolean cancellationRequested) {
    }

    private record DecidedBatch(
            String appUserId,
            String rootConversationId,
            String requestId,
            Map<String, String> decisions,
            DecisionAcknowledgement acknowledgement,
            Instant expiresAt) {
    }

    private enum BatchState {
        REGISTERED,
        PUBLISHED,
        DECIDING,
        DECIDED,
        CANCELLED
    }
}
