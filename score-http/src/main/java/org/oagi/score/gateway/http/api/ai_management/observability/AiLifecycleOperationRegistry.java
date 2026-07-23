package org.oagi.score.gateway.http.api.ai_management.observability;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Request-lifetime state machine for observed operations. A key can start once and
 * terminate once; its tombstone rejects duplicate terminals and starts arriving late.
 */
final class AiLifecycleOperationRegistry<K, T> {

    private final ConcurrentMap<K, Slot<T>> operations = new ConcurrentHashMap<>();

    T start(K key, Supplier<T> operation) {
        return operations.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new Slot<>())
                .start(operation).operation();
    }

    boolean startIfAbsent(K key, Supplier<T> operation) {
        return operations.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new Slot<>())
                .start(operation).accepted();
    }

    T terminate(K key, Supplier<T> missingOperation) {
        return operations.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new Slot<>())
                .terminate(missingOperation);
    }

    T active(K key) {
        Slot<T> slot = operations.get(key);
        return slot != null ? slot.active() : null;
    }

    void closeMatching(Predicate<K> matches, Consumer<T> closeActive) {
        operations.forEach((key, slot) -> {
            if (!matches.test(key)) return;
            T active = slot.close();
            if (active != null) closeActive.accept(active);
            operations.remove(key, slot);
        });
    }

    private static final class Slot<T> {
        private T active;
        private boolean terminated;

        private synchronized Start<T> start(Supplier<T> operation) {
            if (terminated) return new Start<>(null, false);
            if (active != null) return new Start<>(active, false);
            active = Objects.requireNonNull(operation.get(), "operation");
            return new Start<>(active, true);
        }

        private synchronized T terminate(Supplier<T> missingOperation) {
            if (terminated) return null;
            terminated = true;
            T result = active != null ? active
                    : Objects.requireNonNull(missingOperation.get(), "missingOperation");
            active = null;
            return result;
        }

        private synchronized T active() {
            return terminated ? null : active;
        }

        private synchronized T close() {
            terminated = true;
            T result = active;
            active = null;
            return result;
        }
    }

    private record Start<T>(T operation, boolean accepted) { }
}
