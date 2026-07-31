package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiPublicExecutionRequestStatus;
import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.api.ai_management.model.AiCancellationOutcome;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Provides user-facing cancellation/status commands over authoritative shared state. */
final class AiRequestControl {

    private final AiRequestStateStore stateStore;
    private final AiRequestSharedStatePolicy policy;
    private final String instanceId;
    private final Consumer<String> progress;

    AiRequestControl(AiRequestStateStore stateStore, AiRequestSharedStatePolicy policy,
                     String instanceId, Consumer<String> progress) {
        this.stateStore = stateStore;
        this.policy = policy;
        this.instanceId = instanceId;
        this.progress = progress;
    }

    AiCancellationResponse cancel(String requestId, String cancellationRequestId,
                                  String conversationId, Long expectedGeneration,
                                  ScoreUser requester) {
        String appUserId = requester.userId().value().toString();
        AiCancellationOutcome outcome = stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = policy.ownedState(storage, requestId, appUserId);
            state = policy.reconcileOverdue(storage, state, Instant.now());
            if (conversationId != null || expectedGeneration != null) {
                if (!Objects.equals(state.conversationId(), conversationId)
                        || expectedGeneration == null
                        || expectedGeneration != state.generation()) {
                    return outcome(state, cancellationRequestId,
                            "STALE_GENERATION", false, false);
                }
            }
            if (state.terminal()) {
                return outcome(state, cancellationRequestId,
                        "ALREADY_TERMINAL", false, false);
            }
            if ("CANCELLING".equals(state.status())) {
                return outcome(state, cancellationRequestId,
                        "ALREADY_CANCELLING", true, true);
            }
            Instant now = Instant.now();
            AiSharedRequestState cancelling = state.cancelling(
                    cancellationRequestId, "CANCELLED", now);
            if ("REGISTERED".equals(state.status())) {
                cancelling = cancelling.terminal("CANCELLED", null, now);
                storage.put(cancelling);
                return outcome(cancelling, cancellationRequestId,
                        "CANCELLED", true, true);
            }
            storage.put(cancelling);
            return outcome(cancelling, cancellationRequestId,
                    "ACKNOWLEDGED", true, true);
        });
        if (outcome.signalOwner()) {
            stateStore.publishStop(requestId,
                    outcome.response().generation() != null
                            ? outcome.response().generation() : -1L);
        }
        return outcome.response();
    }

    AiPublicExecutionRequestStatus status(String requestId, ScoreUser requester) {
        String appUserId = requester.userId().value().toString();
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = policy.ownedState(storage, requestId, appUserId);
            return policy.reconcileOverdue(storage, state, Instant.now()).snapshot();
        });
    }

    Optional<AiPublicExecutionRequestStatus> active(ScoreUser requester) {
        String appUserId = requester.userId().value().toString();
        return stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            return storage.values().stream()
                    .filter(state -> appUserId.equals(state.appUserId()))
                    .filter(state -> policy.isLogicallyActive(state, now))
                    .max(Comparator.comparing(AiSharedRequestState::createdAt))
                    .map(AiSharedRequestState::snapshot);
        });
    }

    boolean shouldDiscardResult(String requestId) {
        return stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            return state == null || "CANCELLING".equals(state.status())
                    || state.terminal() && !"COMPLETED".equals(state.status());
        });
    }

    void admitToolExecution(String requestId) {
        boolean admitted = stateStore.withRequestLock(requestId, storage -> {
            AiSharedRequestState state = storage.get(requestId);
            return state != null && instanceId.equals(state.workerInstanceId())
                    && "RUNNING".equals(state.status());
        });
        if (!admitted) {
            throw new CancellationException(
                    "The assistant request stopped before Tool execution.");
        }
        progress.accept(requestId);
    }

    private AiCancellationOutcome outcome(AiSharedRequestState state,
                                          String cancellationRequestId,
                                          String disposition, boolean acknowledged,
                                          boolean signalOwner) {
        return new AiCancellationOutcome(policy.cancellationResponse(
                state, cancellationRequestId, disposition, acknowledged), signalOwner);
    }
}
