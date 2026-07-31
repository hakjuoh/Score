package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Owns approval-wait expiry, cancellation, and parallel-batch cleanup. */
final class AiApprovalWaitLifecycle {

    private final Duration approvalTimeout;
    private final Map<String, AiChangeApprovalCoordinator.ParallelGroup> groups;
    private final Map<String, AiChangeApprovalCoordinator.PendingBatch> batches;
    private final Function<List<AiPendingChangeApproval>,
            Map<String, AiChangeApprovalResolution>> denied;

    AiApprovalWaitLifecycle(
            Duration approvalTimeout,
            Map<String, AiChangeApprovalCoordinator.ParallelGroup> groups,
            Map<String, AiChangeApprovalCoordinator.PendingBatch> batches,
            Function<List<AiPendingChangeApproval>,
                    Map<String, AiChangeApprovalResolution>> denied) {
        this.approvalTimeout = approvalTimeout;
        this.groups = groups;
        this.batches = batches;
        this.denied = denied;
    }

    void cancelRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) return;
        batches.values().stream().filter(batch -> requestId.equals(batch.requestId()))
                .toList().forEach(this::cancelBatch);
        groups.values().stream().filter(group -> requestId.equals(group.requestId))
                .toList().forEach(this::cancelGroup);
    }

    boolean expire(AiChangeApprovalCoordinator.Waiter waiter) {
        var batch = batches.values().stream()
                .filter(candidate -> candidate.waiters().contains(waiter))
                .findFirst().orElse(null);
        if (batch != null) return cancelBatch(batch);
        removeWaiting(waiter);
        waiter.resolution().complete(denied.apply(waiter.approvals()));
        return true;
    }

    Map<String, AiChangeApprovalResolution> awaitCommitting(
            AiChangeApprovalCoordinator.Waiter waiter) {
        try {
            return waiter.resolution().get(
                    Math.max(1L, approvalTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "The change approval decision was interrupted while committing.", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "The change approval decision outcome is unknown after its transaction deadline.",
                    exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "The change approval could not be completed.", exception.getCause());
        }
    }

    long remainingWaitMillis(AiChangeApprovalCoordinator.Waiter waiter) {
        Instant expiresAt = waiter.approvals().stream()
                .map(approval -> approval.notice().expiresAt())
                .min(Instant::compareTo)
                .filter(expiration -> expiration.isBefore(waiter.waitDeadline()))
                .orElse(waiter.waitDeadline());
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() || remaining.isZero()
                ? 1L : Math.max(1L, remaining.toMillis() + 1L);
    }

    void removeWaiting(AiChangeApprovalCoordinator.Waiter waiter) {
        if (!waiter.scope().parallel()) return;
        var group = groups.get(waiter.scope().parallelGroupId());
        if (group == null) return;
        synchronized (group) {
            group.waiting.remove(waiter.scope().participantId(), waiter);
        }
    }

    void cancelGroup(AiChangeApprovalCoordinator.ParallelGroup group) {
        if (!groups.remove(group.id, group)) return;
        List<AiChangeApprovalCoordinator.Waiter> waiting;
        AiChangeApprovalCoordinator.PendingBatch activeBatch;
        synchronized (group) {
            waiting = List.copyOf(group.waiting.values());
            activeBatch = group.activeBatch;
            group.waiting.clear();
            group.activeBatch = null;
        }
        boolean activeCancelled = activeBatch == null || cancelBatch(activeBatch);
        waiting.forEach(waiter -> {
            if (activeCancelled || activeBatch == null
                    || !activeBatch.waiters().contains(waiter)) {
                waiter.resolution().complete(denied.apply(waiter.approvals()));
            }
        });
    }

    boolean cancelBatch(AiChangeApprovalCoordinator.PendingBatch batch) {
        synchronized (batch) {
            var state = batch.state().get();
            if (state == AiChangeApprovalCoordinator.BatchState.DECIDING) {
                batch.cancellationRequested().set(true);
                return false;
            }
            if (state == AiChangeApprovalCoordinator.BatchState.DECIDED
                    || state == AiChangeApprovalCoordinator.BatchState.CANCELLED
                    || !batches.remove(batch.id(), batch)) {
                return state == AiChangeApprovalCoordinator.BatchState.CANCELLED;
            }
            batch.state().set(AiChangeApprovalCoordinator.BatchState.CANCELLED);
        }
        clearParallelBatch(batch);
        batch.waiters().forEach(waiter ->
                waiter.resolution().complete(denied.apply(waiter.approvals())));
        return true;
    }

    void clearParallelBatch(AiChangeApprovalCoordinator.PendingBatch batch) {
        if (batch.parallelGroup() == null) return;
        synchronized (batch.parallelGroup()) {
            if (batch.parallelGroup().activeBatch == batch) {
                batch.parallelGroup().activeBatch = null;
            }
            batch.waiters().forEach(waiter -> batch.parallelGroup().waiting.remove(
                    waiter.scope().participantId(), waiter));
        }
    }
}
