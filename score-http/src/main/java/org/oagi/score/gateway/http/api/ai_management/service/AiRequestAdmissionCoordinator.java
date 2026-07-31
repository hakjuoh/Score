package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Owns cluster-wide request admission and conversation maintenance reservations. */
final class AiRequestAdmissionCoordinator {

    private static final int MAX_REGISTRY_ENTRIES = 10_000;
    private static final int MAX_ACTIVE_REQUESTS_PER_USER = 8;
    private static final Duration MAINTENANCE_LEASE = Duration.ofMinutes(5);

    private final AiRequestStateStore stateStore;
    private final AiRequestSharedStatePolicy policy;
    private final String instanceId;
    private final Duration stopGracePeriod;
    private final Duration terminalRetention;

    AiRequestAdmissionCoordinator(AiRequestStateStore stateStore,
                                  AiRequestSharedStatePolicy policy,
                                  String instanceId, Duration stopGracePeriod,
                                  Duration terminalRetention) {
        this.stateStore = stateStore;
        this.policy = policy;
        this.instanceId = instanceId;
        this.stopGracePeriod = stopGracePeriod;
        this.terminalRetention = terminalRetention;
    }

    AiSharedRequestState reserve(String requestId, String conversationId,
                                 ScoreUser requester, Instant deadline) {
        String appUserId = requester.userId().value().toString();
        long generation = ThreadLocalRandom.current().nextLong(1L, 1L << 53);
        Instant now = Instant.now();
        AiSharedRequestState state = new AiSharedRequestState(
                requestId, conversationId, appUserId, instanceId, generation,
                deadline, deadline.plus(stopGracePeriod).plus(terminalRetention),
                now, now, null, null, "REGISTERED", null, "CANCELLED",
                null, null, null, 0L, false, 0, false);
        stateStore.withGlobalLock(storage -> {
            var states = storage.values();
            if (states.size() >= MAX_REGISTRY_ENTRIES) {
                throw new IllegalStateException(
                        "The AI request registry is at capacity. Try again later.");
            }
            long activeForUser = states.stream()
                    .filter(candidate -> appUserId.equals(candidate.appUserId()))
                    .filter(candidate -> policy.isLogicallyActive(candidate, now)).count();
            if (activeForUser >= MAX_ACTIVE_REQUESTS_PER_USER) {
                throw new IllegalStateException(
                        "Too many AI requests are already active for this user.");
            }
            if (storage.get(requestId) != null) {
                throw new IllegalArgumentException(
                        "An AI request with this requestId already exists.");
            }
            if (conversationId != null && (storage.maintenanceOwner(conversationId) != null
                    || states.stream().anyMatch(candidate ->
                    conversationId.equals(candidate.conversationId())
                            && policy.isLogicallyActive(candidate, now)))) {
                throw new IllegalStateException(
                        "This AI conversation already has an active request.");
            }
            storage.put(state);
            return null;
        });
        return state;
    }

    void bind(AiRequestRegistry.Entry entry, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException(
                    "The prepared AI request must have a conversation ID.");
        }
        stateStore.withGlobalAndRequestLock(entry.requestId(), storage -> {
            AiSharedRequestState state = policy.exactOwnerState(storage, entry);
            if (!"REGISTERED".equals(state.status())) {
                throw new IllegalStateException(
                        "The AI request stopped before preparation completed.");
            }
            if (state.conversationId() != null
                    && !state.conversationId().equals(conversationId)) {
                throw new IllegalStateException(
                        "The prepared AI conversation does not match its reservation.");
            }
            if (state.conversationId() == null) {
                if (storage.maintenanceOwner(conversationId) != null
                        || storage.values().stream().anyMatch(candidate ->
                        !entry.requestId().equals(candidate.requestId())
                                && conversationId.equals(candidate.conversationId())
                                && policy.isLogicallyActive(candidate, Instant.now()))) {
                    throw new IllegalStateException(
                            "This AI conversation already has an active request.");
                }
                storage.put(state.withConversation(conversationId, Instant.now()));
            }
            return null;
        });
    }

    <T> T whileConversationIdle(String conversationId, Supplier<T> action) {
        String token = maintenanceToken();
        stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            if (storage.maintenanceOwner(conversationId) != null
                    || storage.values().stream().anyMatch(state ->
                    conversationId.equals(state.conversationId())
                            && policy.isLogicallyActive(state, now))) {
                throw new IllegalStateException(
                        "Stop the active AI request before changing its conversation.");
            }
            storage.putMaintenance(conversationId, token, MAINTENANCE_LEASE);
            return null;
        });
        try {
            return action.get();
        } finally {
            releaseMaintenance(Set.of(conversationId), token);
        }
    }

    <T> T whileRequestAndConversationIdle(String requestId, String conversationId,
                                          Supplier<T> action) {
        if (requestId == null || requestId.isBlank() || conversationId == null
                || conversationId.isBlank() || action == null) {
            throw new IllegalArgumentException(
                    "An AI request maintenance action is incomplete.");
        }
        String token = maintenanceToken();
        Set<String> conversations = stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            AiSharedRequestState source = storage.get(requestId);
            if (source != null && policy.isLogicallyActive(source, now)) {
                throw new IllegalStateException(
                        "Stop the active AI request before deciding its change confirmation.");
            }
            Set<String> guarded = new LinkedHashSet<>();
            guarded.add(conversationId);
            if (source != null && source.conversationId() != null
                    && !source.conversationId().isBlank()) {
                guarded.add(source.conversationId());
            }
            if (guarded.stream().anyMatch(id -> storage.maintenanceOwner(id) != null)
                    || storage.values().stream().anyMatch(state ->
                    (requestId.equals(state.requestId())
                            || guarded.contains(state.conversationId()))
                            && policy.isLogicallyActive(state, now))) {
                throw new IllegalStateException(
                        "Stop the active AI request before deciding its change confirmation.");
            }
            guarded.forEach(id -> storage.putMaintenance(id, token, MAINTENANCE_LEASE));
            return Set.copyOf(guarded);
        });
        try {
            return action.get();
        } finally {
            releaseMaintenance(conversations, token);
        }
    }

    boolean hasActiveConversation(String conversationId) {
        return stateStore.withGlobalLock(storage -> {
            Instant now = Instant.now();
            return storage.maintenanceOwner(conversationId) != null
                    || storage.values().stream().anyMatch(state ->
                    conversationId.equals(state.conversationId())
                            && policy.isLogicallyActive(state, now));
        });
    }

    private void releaseMaintenance(Set<String> conversations, String token) {
        stateStore.withGlobalLock(storage -> {
            conversations.forEach(id -> storage.removeMaintenance(id, token));
            return null;
        });
    }

    private String maintenanceToken() {
        return instanceId + ":maintenance:" + UUID.randomUUID();
    }
}
