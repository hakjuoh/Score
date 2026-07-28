package org.oagi.score.gateway.http.api.ai_management.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.model.AiRequestStopSignal;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/** Atomic shared-state operations required by the multi-instance request registry. */
public interface AiRequestStateStore {

    static AiRequestStateStore inMemory() {
        return new InMemoryAiRequestStateStore();
    }

    <T> T withGlobalLock(Function<Storage, T> operation);

    <T> T withRequestLock(String requestId, Function<Storage, T> operation);

    default <T> T withGlobalAndRequestLock(String requestId, Function<Storage, T> operation) {
        return withGlobalLock(ignored -> withRequestLock(requestId, operation));
    }

    void publishStop(String requestId, long generation);

    void addStopListener(Consumer<AiRequestStopSignal> listener);

    interface Storage {
        AiSharedRequestState get(String requestId);
        Collection<AiSharedRequestState> values();
        void put(AiSharedRequestState state);
        void remove(String requestId);
        String maintenanceOwner(String conversationId);
        void putMaintenance(String conversationId, String owner, Duration ttl);
        void removeMaintenance(String conversationId, String owner);
    }

}

@Component
final class RedisAiRequestStateStore implements AiRequestStateStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisAiRequestStateStore.class);
    private static final String GLOBAL_LOCK = "score:ai:request-state:lock";
    private static final String REQUEST_LOCK_PREFIX = "score:ai:request-state:request-lock:";
    private static final String REQUEST_MAP = "score:ai:request-state:requests";
    private static final String MAINTENANCE_MAP = "score:ai:request-state:maintenance";
    private static final String STOP_TOPIC = "score:ai:request-state:stop";
    private static final Duration TERMINAL_RETENTION = Duration.ofMinutes(30);
    /** Every critical section is a handful of Redis map commands. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);

    private final RedissonClient redisson;
    private final RMapCache<String, AiSharedRequestState> requests;
    private final RMapCache<String, String> maintenance;
    private final RTopic stopTopic;

    @SuppressWarnings("unchecked")
    RedisAiRequestStateStore(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.requests = redisson.getMapCache(REQUEST_MAP,
                new TypedJsonJacksonCodec(String.class, AiSharedRequestState.class,
                        objectMapper.copy().findAndRegisterModules()));
        this.maintenance = redisson.getMapCache(MAINTENANCE_MAP, StringCodec.INSTANCE);
        this.stopTopic = redisson.getTopic(STOP_TOPIC, StringCodec.INSTANCE);
    }

    @Override
    public <T> T withGlobalLock(Function<Storage, T> operation) {
        return locked(redisson.getLock(GLOBAL_LOCK), operation);
    }

    @Override
    public <T> T withRequestLock(String requestId, Function<Storage, T> operation) {
        return locked(redisson.getLock(REQUEST_LOCK_PREFIX + requestId), operation);
    }

    @Override
    public void publishStop(String requestId, long generation) {
        stopTopic.publish(requestId + "\n" + generation);
    }

    @Override
    public void addStopListener(Consumer<AiRequestStopSignal> listener) {
        stopTopic.addListener(String.class, (channel, message) -> {
            int separator = message.lastIndexOf('\n');
            if (separator <= 0) {
                return;
            }
            try {
                listener.accept(new AiRequestStopSignal(message.substring(0, separator),
                        Long.parseLong(message.substring(separator + 1))));
            } catch (NumberFormatException ignored) {
                // Ignore malformed messages from an incompatible publisher.
            }
        });
    }

    /**
     * Redisson's synchronous calls fail fast on an interrupted thread. An
     * interrupt that lands while an acquisition is in flight therefore throws
     * before the caller reaches its unlock even though the server already
     * granted the lock, and the lock watchdog then renews that lease forever.
     * The interrupt is withheld across the critical section and the lease is
     * bounded so a lost unlock expires instead of wedging the request.
     */
    private <T> T locked(RLock lock, Function<Storage, T> operation) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!lock.tryLock(LOCK_WAIT.toMillis(), LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AiSharedStateUnavailableException(
                        "The AI request state stayed locked for " + LOCK_WAIT.toSeconds()
                                + " seconds. Try again.");
            }
            try {
                return operation.apply(new RedisStorage());
            } finally {
                unlockQuietly(lock);
            }
        } catch (InterruptedException interruption) {
            interrupted = true;
            throw new AiSharedStateUnavailableException(
                    "The AI request state access was interrupted.", interruption);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (RuntimeException failure) {
            // The bounded lease releases the lock on its own. Failing here
            // would replace the operation's outcome with a release problem.
            LOGGER.warn("Could not release the AI request state lock {}.", lock.getName(), failure);
        }
    }

    private final class RedisStorage implements Storage {
        @Override public AiSharedRequestState get(String requestId) { return requests.get(requestId); }
        @Override public Collection<AiSharedRequestState> values() { return new ArrayList<>(requests.values()); }
        @Override public void remove(String requestId) { requests.remove(requestId); }
        @Override public String maintenanceOwner(String conversationId) { return maintenance.get(conversationId); }

        @Override
        public void put(AiSharedRequestState state) {
            Duration ttl = state.terminal()
                    ? TERMINAL_RETENTION
                    : Duration.between(Instant.now(), state.expiresAt());
            if (ttl.isNegative() || ttl.isZero()) {
                ttl = Duration.ofSeconds(1);
            }
            requests.put(state.requestId(), state, ttl.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void putMaintenance(String conversationId, String owner, Duration ttl) {
            maintenance.put(conversationId, owner, ttl.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void removeMaintenance(String conversationId, String owner) {
            maintenance.remove(conversationId, owner);
        }
    }
}

/** Shared in-memory implementation used to model multiple instances in unit tests. */
final class InMemoryAiRequestStateStore implements AiRequestStateStore {

    private final Object globalLock = new Object();
    private final Map<String, ReentrantLock> requestLocks = new ConcurrentHashMap<>();
    private final Map<String, AiSharedRequestState> requests = new ConcurrentHashMap<>();
    private final Map<String, String> maintenance = new ConcurrentHashMap<>();
    private final List<Consumer<AiRequestStopSignal>> stopListeners = new ArrayList<>();

    @Override
    public <T> T withGlobalLock(Function<Storage, T> operation) {
        synchronized (globalLock) {
            return operation.apply(new MemoryStorage());
        }
    }

    @Override
    public <T> T withRequestLock(String requestId, Function<Storage, T> operation) {
        ReentrantLock lock = requestLocks.computeIfAbsent(requestId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.apply(new MemoryStorage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void publishStop(String requestId, long generation) {
        List.copyOf(stopListeners).forEach(
                listener -> listener.accept(new AiRequestStopSignal(requestId, generation)));
    }

    @Override
    public void addStopListener(Consumer<AiRequestStopSignal> listener) {
        stopListeners.add(listener);
    }

    private final class MemoryStorage implements Storage {
        @Override public AiSharedRequestState get(String requestId) { return requests.get(requestId); }
        @Override public Collection<AiSharedRequestState> values() { return List.copyOf(requests.values()); }
        @Override public void put(AiSharedRequestState state) { requests.put(state.requestId(), state); }
        @Override public void remove(String requestId) { requests.remove(requestId); }
        @Override public String maintenanceOwner(String conversationId) { return maintenance.get(conversationId); }
        @Override public void putMaintenance(String conversationId, String owner, Duration ttl) {
            maintenance.put(conversationId, owner);
        }
        @Override public void removeMaintenance(String conversationId, String owner) {
            maintenance.remove(conversationId, owner);
        }
    }
}
