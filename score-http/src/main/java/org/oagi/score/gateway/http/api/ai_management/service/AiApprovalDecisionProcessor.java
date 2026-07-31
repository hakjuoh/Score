package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeDecision;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Validates, commits, and idempotently replays complete approval decisions. */
final class AiApprovalDecisionProcessor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiApprovalDecisionProcessor.class);

    private final AiChangeConfirmationService confirmations;
    private final AiApprovalTrajectoryWriter trajectoryWriter;
    private final AiApprovalDecisionLedger decisionLedger;
    private final Map<String, AiChangeApprovalCoordinator.PendingBatch> batches;
    private final Consumer<AiChangeApprovalCoordinator.PendingBatch> clearParallelBatch;
    private final Function<AiChangeApprovalCoordinator.PendingBatch, Boolean> cancelBatch;
    private final Function<List<AiPendingChangeApproval>,
            Map<String, AiChangeApprovalResolution>> denied;

    AiApprovalDecisionProcessor(
            AiChangeConfirmationService confirmations,
            AiApprovalTrajectoryWriter trajectoryWriter,
            AiApprovalDecisionLedger decisionLedger,
            Map<String, AiChangeApprovalCoordinator.PendingBatch> batches,
            Consumer<AiChangeApprovalCoordinator.PendingBatch> clearParallelBatch,
            Function<AiChangeApprovalCoordinator.PendingBatch, Boolean> cancelBatch,
            Function<List<AiPendingChangeApproval>,
                    Map<String, AiChangeApprovalResolution>> denied) {
        this.confirmations = confirmations;
        this.trajectoryWriter = trajectoryWriter;
        this.decisionLedger = decisionLedger;
        this.batches = batches;
        this.clearParallelBatch = clearParallelBatch;
        this.cancelBatch = cancelBatch;
        this.denied = denied;
    }

    AiChangeApprovalCoordinator.DecisionAcknowledgement decide(
            ScoreUser requester, AiChangeApprovalDecisionRequest command) {
        validateCommand(requester, command);
        Map<String, String> requested = decisions(command.decisions());
        if (decisionLedger.contains(command.batchId())) {
            return decisionLedger.replay(requester, command, requested);
        }
        AiChangeApprovalCoordinator.PendingBatch batch = batches.get(command.batchId());
        if (batch == null) return decisionLedger.replay(requester, command, requested);
        validateIdentity(requester, command, requested, batch);
        synchronized (batch) {
            if (batches.get(batch.id()) != batch) {
                return replayOrThrow(requester, command, requested,
                        "The change approval batch was already answered.");
            }
            if (batch.state().get() != AiChangeApprovalCoordinator.BatchState.PUBLISHED) {
                return replayOrThrow(requester, command, requested,
                        "The change approval batch is not awaiting a decision.");
            }
            if (!Instant.now().isBefore(batch.expiresAt())) {
                cancelBatch.apply(batch);
                throw new IllegalArgumentException(
                        "The change approval batch has expired.");
            }
            batch.state().set(AiChangeApprovalCoordinator.BatchState.DECIDING);
        }
        try {
            registerDeadlineFence(batch);
            List<AiChangeApprovalCoordinator.BatchItem> items =
                    List.copyOf(batch.items().values());
            List<AiChangeDecision> applied = confirmations.decideBatch(requester,
                    items.stream().map(item -> new AiChangeConfirmationService.BatchDecision(
                            item.waiter().sourceConversationId(),
                            item.approval().notice().confirmationRequestId(),
                            requested.get(item.approval().notice().confirmationRequestId())))
                            .toList());
            if (!Instant.now().isBefore(batch.decisionDeadline())) {
                throw new IllegalStateException(
                        "The change approval decision exceeded its approval deadline.");
            }
            Map<AiChangeApprovalCoordinator.Waiter,
                    Map<String, AiChangeApprovalResolution>> byWaiter =
                    resolutions(items, applied, requested);
            trajectoryWriter.decided(requester, batch.rootConversationId(), batch.requestId(),
                    batch.id(), batch.generation(), requested);
            long approved = requested.values().stream().filter("APPROVE"::equals).count();
            var acknowledgement = new AiChangeApprovalCoordinator.DecisionAcknowledgement(
                    batch.id(), approved, requested.size() - approved);
            completeAfterCommit(batch, byWaiter, requested, acknowledgement);
            return null;
        } catch (RuntimeException failure) {
            rollbackDecision(batch);
            throw failure;
        }
    }

    private void validateCommand(ScoreUser requester, AiChangeApprovalDecisionRequest command) {
        if (requester == null || command == null || !StringUtils.hasText(command.batchId())
                || !StringUtils.hasText(command.requestId())
                || !StringUtils.hasText(command.conversationId())) {
            throw new IllegalArgumentException("A change approval decision is incomplete.");
        }
    }

    private void validateIdentity(ScoreUser requester, AiChangeApprovalDecisionRequest command,
                                  Map<String, String> requested,
                                  AiChangeApprovalCoordinator.PendingBatch batch) {
        if (!batch.appUserId().equals(requester.userId().value().toString())) {
            throw new AccessDeniedException(
                    "The change approval batch belongs to another user.");
        }
        if (!batch.requestId().equals(command.requestId())
                || !batch.rootConversationId().equals(command.conversationId())) {
            throw new IllegalArgumentException(
                    "The change approval batch identity does not match.");
        }
        Set<String> expected = new LinkedHashSet<>(batch.items().keySet());
        if (!requested.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "Every change approval item requires exactly one decision.");
        }
    }

    private AiChangeApprovalCoordinator.DecisionAcknowledgement replayOrThrow(
            ScoreUser requester, AiChangeApprovalDecisionRequest command,
            Map<String, String> requested, String message) {
        if (decisionLedger.contains(command.batchId())) {
            return decisionLedger.replay(requester, command, requested);
        }
        throw new IllegalArgumentException(message);
    }

    private Map<AiChangeApprovalCoordinator.Waiter,
            Map<String, AiChangeApprovalResolution>> resolutions(
            List<AiChangeApprovalCoordinator.BatchItem> items,
            List<AiChangeDecision> applied, Map<String, String> requested) {
        Map<AiChangeApprovalCoordinator.Waiter,
                Map<String, AiChangeApprovalResolution>> byWaiter = new LinkedHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            String requestedDecision = requested.get(
                    item.approval().notice().confirmationRequestId());
            var resolution = new AiChangeApprovalResolution(
                    item.approval().notice().confirmationRequestId(),
                    "APPROVE".equals(requestedDecision)
                            ? AiChangeApprovalResolution.Decision.APPROVE
                            : AiChangeApprovalResolution.Decision.DENY,
                    applied.get(index).response().confirmationGrant());
            if (resolution.decision() == AiChangeApprovalResolution.Decision.APPROVE
                    && !resolution.approved()) {
                throw new IllegalStateException(
                        "An approved change did not produce a one-time grant.");
            }
            byWaiter.computeIfAbsent(item.waiter(), ignored -> new LinkedHashMap<>())
                    .put(resolution.confirmationRequestId(), resolution);
        }
        return byWaiter;
    }

    private void completeAfterCommit(
            AiChangeApprovalCoordinator.PendingBatch batch,
            Map<AiChangeApprovalCoordinator.Waiter,
                    Map<String, AiChangeApprovalResolution>> byWaiter,
            Map<String, String> decisions,
            AiChangeApprovalCoordinator.DecisionAcknowledgement acknowledgement) {
        Runnable completion = () -> completeDecision(batch, byWaiter);
        Runnable committed = () -> {
            decisionLedger.remember(batch.id(), batch.appUserId(), batch.rootConversationId(),
                    batch.requestId(), decisions, acknowledgement);
            try {
                batch.waiters().getFirst().acknowledgementConsumer().accept(acknowledgement);
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not publish a committed change approval acknowledgement", failure);
            } finally {
                completion.run();
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { committed.run(); }
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) rollbackDecision(batch);
            }
        });
    }

    private void registerDeadlineFence(AiChangeApprovalCoordinator.PendingBatch batch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if (!Instant.now().isBefore(batch.decisionDeadline())) {
                    throw new IllegalStateException(
                            "The change approval decision exceeded its approval deadline.");
                }
            }
        });
    }

    private void completeDecision(
            AiChangeApprovalCoordinator.PendingBatch batch,
            Map<AiChangeApprovalCoordinator.Waiter,
                    Map<String, AiChangeApprovalResolution>> byWaiter) {
        synchronized (batch) {
            if (batch.state().get() != AiChangeApprovalCoordinator.BatchState.DECIDING
                    || !batches.remove(batch.id(), batch)) return;
            batch.state().set(AiChangeApprovalCoordinator.BatchState.DECIDED);
        }
        clearParallelBatch.accept(batch);
        byWaiter.forEach((waiter, values) -> waiter.resolution().complete(
                Collections.unmodifiableMap(new LinkedHashMap<>(values))));
    }

    private void rollbackDecision(AiChangeApprovalCoordinator.PendingBatch batch) {
        boolean cancel;
        synchronized (batch) {
            if (batch.state().get() != AiChangeApprovalCoordinator.BatchState.DECIDING) return;
            cancel = batch.cancellationRequested().get()
                    || !Instant.now().isBefore(batch.decisionDeadline());
            batch.state().set(cancel ? AiChangeApprovalCoordinator.BatchState.CANCELLED
                    : AiChangeApprovalCoordinator.BatchState.PUBLISHED);
            if (cancel) batches.remove(batch.id(), batch);
        }
        if (cancel) {
            clearParallelBatch.accept(batch);
            batch.waiters().forEach(waiter ->
                    waiter.resolution().complete(denied.apply(waiter.approvals())));
        }
    }

    private Map<String, String> decisions(
            List<AiChangeApprovalDecisionRequest.ItemDecision> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var item : values) {
            String decision = StringUtils.hasText(item.decision())
                    ? item.decision().strip().toUpperCase() : "";
            if (!StringUtils.hasText(item.confirmationRequestId())
                    || (!"APPROVE".equals(decision) && !"DENY".equals(decision))
                    || result.putIfAbsent(item.confirmationRequestId(), decision) != null) {
                throw new IllegalArgumentException(
                        "Change approval decisions must be unique APPROVE or DENY values.");
            }
        }
        return Map.copyOf(result);
    }
}
