package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;

/**
 * Centralizes distributed request ownership and overdue-state semantics.
 *
 * Keeping these rules outside the local lifecycle registry makes it harder for
 * admission, cancellation, and status queries to disagree about authoritative
 * shared state.
 */
final class AiRequestSharedStatePolicy {

    private static final Duration DISTRIBUTED_RECONCILIATION_LAG = Duration.ofSeconds(1);

    private final AiRequestStateStore stateStore;
    private final String instanceId;
    private final Duration stopGracePeriod;

    AiRequestSharedStatePolicy(AiRequestStateStore stateStore, String instanceId,
                               Duration stopGracePeriod) {
        this.stateStore = stateStore;
        this.instanceId = instanceId;
        this.stopGracePeriod = stopGracePeriod;
    }

    void rollbackRegistration(AiSharedRequestState expected) {
        stateStore.withRequestLock(expected.requestId(), storage -> {
            AiSharedRequestState current = storage.get(expected.requestId());
            if (current != null && current.generation() == expected.generation()
                    && instanceId.equals(current.workerInstanceId())) {
                storage.remove(expected.requestId());
            }
            return null;
        });
    }

    AiSharedRequestState exactOwnerState(AiRequestStateStore.Storage storage,
                                         AiRequestRegistry.Entry entry) {
        AiSharedRequestState state = storage.get(entry.requestId());
        if (!matchesOwner(state, entry)) {
            throw new IllegalStateException(
                    "The AI request reservation is no longer owned by this instance.");
        }
        return state;
    }

    boolean matchesOwner(AiSharedRequestState state, AiRequestRegistry.Entry entry) {
        return state != null && state.generation() == entry.generation()
                && instanceId.equals(state.workerInstanceId());
    }

    AiSharedRequestState ownedState(AiRequestStateStore.Storage storage,
                                    String requestId, String appUserId) {
        AiSharedRequestState state = storage.get(requestId);
        if (state == null || !appUserId.equals(state.appUserId())) {
            throw new AccessDeniedException(
                    "AI request does not exist or is not owned by the signed-in user.");
        }
        return state;
    }

    AiSharedRequestState reconcileOverdue(AiRequestStateStore.Storage storage,
                                          AiSharedRequestState state, Instant now) {
        if (state.terminal() || now.isBefore(reconciliationAt(state))) {
            return state;
        }
        String terminalStatus;
        String reason;
        if ("CANCELLING".equals(state.status())) {
            terminalStatus = state.changeOutcomeUncertain()
                    ? "UNKNOWN_RECONCILIATION_REQUIRED" : state.terminalTarget();
            reason = state.changeOutcomeUncertain()
                    ? "CHANGE_OUTCOME_UNCERTAIN" : "WORKER_INSTANCE_UNAVAILABLE";
        } else {
            terminalStatus = state.changeObserved()
                    ? "UNKNOWN_RECONCILIATION_REQUIRED" : "TIMED_OUT";
            reason = "WORKER_INSTANCE_UNAVAILABLE";
        }
        AiSharedRequestState reconciled = state.terminal(terminalStatus, reason, now);
        storage.put(reconciled);
        return reconciled;
    }

    boolean isLogicallyActive(AiSharedRequestState state, Instant now) {
        return !state.terminal() && now.isBefore(reconciliationAt(state));
    }

    AiCancellationResponse cancellationResponse(
            AiSharedRequestState state, String requestedCancellationId,
            String disposition, boolean acknowledged) {
        return new AiCancellationResponse(state.requestId(), state.conversationId(), state.generation(),
                requestedCancellationId, state.cancellationRequestId(), disposition, state.status(),
                acknowledged, state.terminal(), state.lastEventSequence(),
                state.cancellationRequestedAt(), state.cancellationAcknowledgedAt(),
                state.deadline(), state.terminalAt());
    }

    private Instant reconciliationAt(AiSharedRequestState state) {
        Instant localWatchdogAt = "CANCELLING".equals(state.status())
                && state.cancellationRequestedAt() != null
                ? state.cancellationRequestedAt().plus(stopGracePeriod)
                : state.deadline().plus(stopGracePeriod);
        // Give the owning instance's watchdog a deterministic opportunity to report a
        // stuck live worker before another instance treats the owner as unavailable.
        return localWatchdogAt.plus(DISTRIBUTED_RECONCILIATION_LAG);
    }
}
