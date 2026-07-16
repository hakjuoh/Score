package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiPublicExecutionRequestStatus;

import java.time.Instant;

/** Redis-persisted authority for one AI request across application instances. */
public record AiSharedRequestState(
        String requestId,
        String conversationId,
        String appUserId,
        String workerInstanceId,
        long generation,
        Instant deadline,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant terminalAt,
        String status,
        String statusReason,
        String terminalTarget,
        String cancellationRequestId,
        Instant cancellationRequestedAt,
        Instant cancellationAcknowledgedAt,
        long lastEventSequence,
        boolean mutationObserved,
        int mutationInFlight,
        boolean mutationOutcomeUncertain) {

    public boolean terminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "TIMED_OUT".equals(status)
                || "UNKNOWN_RECONCILIATION_REQUIRED".equals(status);
    }

    public AiSharedRequestState withConversation(String value, Instant now) {
        return copy(value, updatedAt(now), status, statusReason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, mutationObserved, mutationInFlight, mutationOutcomeUncertain);
    }

    public AiSharedRequestState started(Instant now) {
        return copy(conversationId, now, "RUNNING", null, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, mutationObserved, mutationInFlight, mutationOutcomeUncertain,
                now, null);
    }

    public AiSharedRequestState cancelling(String cancellationId, String target, Instant now) {
        return copy(conversationId, now, "CANCELLING", statusReason, target,
                cancellationId, now, now, lastEventSequence + 1,
                mutationObserved, mutationInFlight, mutationObserved);
    }

    public AiSharedRequestState timingOut(Instant now, boolean workerPresent) {
        if (!workerPresent) {
            return terminal("TIMED_OUT", "DEADLINE_EXCEEDED", now);
        }
        return copy(conversationId, now, "CANCELLING", "DEADLINE_EXCEEDED", "TIMED_OUT",
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence + 1, mutationObserved, mutationInFlight, mutationObserved);
    }

    public AiSharedRequestState mutationStarted(Instant now) {
        return copy(conversationId, now, status, statusReason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, true, mutationInFlight + 1, mutationOutcomeUncertain);
    }

    public AiSharedRequestState mutationFinished(Instant now) {
        return copy(conversationId, now, status, statusReason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, mutationObserved, Math.max(0, mutationInFlight - 1),
                mutationOutcomeUncertain);
    }

    public AiSharedRequestState terminal(String terminalStatus, String reason, Instant now) {
        return copy(conversationId, now, terminalStatus, reason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence + 1, mutationObserved, mutationInFlight,
                mutationOutcomeUncertain, startedAt, now);
    }

    public AiPublicExecutionRequestStatus snapshot() {
        return new AiPublicExecutionRequestStatus(conversationId, requestId, generation,
                "connectcenter-assistant", status, statusReason, deadline, 0,
                null, updatedAt, createdAt, updatedAt, startedAt, terminalAt,
                cancellationRequestId, cancellationRequestedAt, cancellationRequestedAt,
                cancellationAcknowledgedAt, lastEventSequence, 1L);
    }

    private AiSharedRequestState copy(
            String nextConversationId, Instant nextUpdatedAt, String nextStatus,
            String nextStatusReason, String nextTerminalTarget, String nextCancellationRequestId,
            Instant nextCancellationRequestedAt, Instant nextCancellationAcknowledgedAt,
            long nextLastEventSequence, boolean nextMutationObserved, int nextMutationInFlight,
            boolean nextMutationOutcomeUncertain) {
        return copy(nextConversationId, nextUpdatedAt, nextStatus, nextStatusReason,
                nextTerminalTarget, nextCancellationRequestId, nextCancellationRequestedAt,
                nextCancellationAcknowledgedAt, nextLastEventSequence, nextMutationObserved,
                nextMutationInFlight, nextMutationOutcomeUncertain, startedAt, terminalAt);
    }

    private AiSharedRequestState copy(
            String nextConversationId, Instant nextUpdatedAt, String nextStatus,
            String nextStatusReason, String nextTerminalTarget, String nextCancellationRequestId,
            Instant nextCancellationRequestedAt, Instant nextCancellationAcknowledgedAt,
            long nextLastEventSequence, boolean nextMutationObserved, int nextMutationInFlight,
            boolean nextMutationOutcomeUncertain, Instant nextStartedAt, Instant nextTerminalAt) {
        return new AiSharedRequestState(requestId, nextConversationId, appUserId,
                workerInstanceId, generation, deadline, expiresAt, createdAt, nextUpdatedAt,
                nextStartedAt, nextTerminalAt, nextStatus, nextStatusReason, nextTerminalTarget,
                nextCancellationRequestId, nextCancellationRequestedAt,
                nextCancellationAcknowledgedAt, nextLastEventSequence, nextMutationObserved,
                nextMutationInFlight, nextMutationOutcomeUncertain);
    }

    private Instant updatedAt(Instant now) {
        return now != null ? now : updatedAt;
    }
}
