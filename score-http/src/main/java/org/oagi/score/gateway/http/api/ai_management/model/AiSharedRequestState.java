package org.oagi.score.gateway.http.api.ai_management.model;

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
        boolean changeObserved,
        int changeInFlight,
        boolean changeOutcomeUncertain) {

    public boolean terminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "TIMED_OUT".equals(status)
                || "UNKNOWN_RECONCILIATION_REQUIRED".equals(status);
    }

    public AiSharedRequestState withConversation(String value, Instant now) {
        return copy(value, updatedAt(now), status, statusReason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, changeObserved, changeInFlight, changeOutcomeUncertain);
    }

    public AiSharedRequestState started(Instant now) {
        return copy(conversationId, now, "RUNNING", null, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, changeObserved, changeInFlight, changeOutcomeUncertain,
                now, null);
    }

    public AiSharedRequestState cancelling(String cancellationId, String target, Instant now) {
        return copy(conversationId, now, "CANCELLING", statusReason, target,
                cancellationId, now, now, lastEventSequence + 1,
                changeObserved, changeInFlight, changeObserved);
    }

    public AiSharedRequestState timingOut(Instant now, boolean workerPresent) {
        if (!workerPresent) {
            return terminal("TIMED_OUT", "INACTIVITY_TIMEOUT", now);
        }
        return copy(conversationId, now, "CANCELLING", "INACTIVITY_TIMEOUT", "TIMED_OUT",
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence + 1, changeObserved, changeInFlight, changeObserved);
    }

    /** Publishes the owning worker's next inactivity boundary without changing lifecycle state. */
    public AiSharedRequestState leaseRenewed(Instant renewedDeadline,
                                             Instant renewedExpiresAt,
                                             Instant now) {
        return new AiSharedRequestState(requestId, conversationId, appUserId,
                workerInstanceId, generation, renewedDeadline, renewedExpiresAt,
                createdAt, now, startedAt, terminalAt, status, statusReason,
                terminalTarget, cancellationRequestId, cancellationRequestedAt,
                cancellationAcknowledgedAt, lastEventSequence, changeObserved,
                changeInFlight, changeOutcomeUncertain);
    }

    public AiSharedRequestState changeStarted(Instant now) {
        return copy(conversationId, now, status, statusReason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, true, changeInFlight + 1, changeOutcomeUncertain);
    }

    public AiSharedRequestState changeFinished(Instant now) {
        return copy(conversationId, now, status, statusReason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence, changeObserved, Math.max(0, changeInFlight - 1),
                changeOutcomeUncertain);
    }

    public AiSharedRequestState terminal(String terminalStatus, String reason, Instant now) {
        return copy(conversationId, now, terminalStatus, reason, terminalTarget,
                cancellationRequestId, cancellationRequestedAt, cancellationAcknowledgedAt,
                lastEventSequence + 1, changeObserved, changeInFlight,
                changeOutcomeUncertain, startedAt, now);
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
            long nextLastEventSequence, boolean nextChangeObserved, int nextChangeInFlight,
            boolean nextChangeOutcomeUncertain) {
        return copy(nextConversationId, nextUpdatedAt, nextStatus, nextStatusReason,
                nextTerminalTarget, nextCancellationRequestId, nextCancellationRequestedAt,
                nextCancellationAcknowledgedAt, nextLastEventSequence, nextChangeObserved,
                nextChangeInFlight, nextChangeOutcomeUncertain, startedAt, terminalAt);
    }

    private AiSharedRequestState copy(
            String nextConversationId, Instant nextUpdatedAt, String nextStatus,
            String nextStatusReason, String nextTerminalTarget, String nextCancellationRequestId,
            Instant nextCancellationRequestedAt, Instant nextCancellationAcknowledgedAt,
            long nextLastEventSequence, boolean nextChangeObserved, int nextChangeInFlight,
            boolean nextChangeOutcomeUncertain, Instant nextStartedAt, Instant nextTerminalAt) {
        return new AiSharedRequestState(requestId, nextConversationId, appUserId,
                workerInstanceId, generation, deadline, expiresAt, createdAt, nextUpdatedAt,
                nextStartedAt, nextTerminalAt, nextStatus, nextStatusReason, nextTerminalTarget,
                nextCancellationRequestId, nextCancellationRequestedAt,
                nextCancellationAcknowledgedAt, nextLastEventSequence, nextChangeObserved,
                nextChangeInFlight, nextChangeOutcomeUncertain);
    }

    private Instant updatedAt(Instant now) {
        return now != null ? now : updatedAt;
    }
}
