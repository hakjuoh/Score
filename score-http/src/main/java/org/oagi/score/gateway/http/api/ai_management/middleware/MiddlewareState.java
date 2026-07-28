package org.oagi.score.gateway.http.api.ai_management.middleware;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Typed, invocation-local state shared by the middleware participating in one run. */
public final class MiddlewareState {

    private final ConcurrentMap<Key<?>, Object> values = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MiddlewareState> shadowStates = new ConcurrentHashMap<>();

    public <T> Optional<T> get(Key<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(key.type().cast(values.get(key)));
    }

    public <T> T getOrCreate(Key<T> key, Supplier<? extends T> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        return key.type().cast(values.computeIfAbsent(key,
                ignored -> Objects.requireNonNull(factory.get(), "middleware state value")));
    }

    public <T> void put(Key<T> key, T value) {
        Objects.requireNonNull(key, "key");
        values.put(key, key.type().cast(Objects.requireNonNull(value, "value")));
    }

    /** Returns persistent state visible only to one shadow registration in this invocation. */
    MiddlewareState shadowState(String middlewareId) {
        String id = Objects.requireNonNull(middlewareId, "middlewareId");
        return shadowStates.computeIfAbsent(id, ignored -> new MiddlewareState());
    }

    /** Namespaces state by registered middleware id to prevent accidental key collisions. */
    public record Key<T>(String middlewareId, String name, Class<T> type) {
        public Key {
            middlewareId = identifier(middlewareId, "middlewareId");
            name = identifier(name, "name");
            Objects.requireNonNull(type, "type");
        }

        private static String identifier(String value, String label) {
            String normalized = Objects.requireNonNull(value, label).strip().toLowerCase();
            if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,79}")) {
                throw new IllegalArgumentException("Invalid middleware state " + label + ": " + value);
            }
            return normalized;
        }
    }
}
