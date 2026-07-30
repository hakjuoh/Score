package org.oagi.score.gateway.http.api.ai_management.execution;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Passive observer; failures are isolated and cannot alter an Agent result. */
@FunctionalInterface
public interface ExecutionObserver {

    void observe(ExecutionObservation observation);

    /**
     * Publishes one event and performs its durable projection at the same ordering boundary.
     * Implementations without a dispatcher retain deterministic write-before-observe behavior.
     */
    default void publish(ExecutionObservation observation,
                         Consumer<ExecutionObservation> durableWrite) {
        publish(observation, durableWrite, ignored -> { });
    }

    /** Adds a request-local projection (for example WebSocket realtime) to the ordered fan-out. */
    default void publish(ExecutionObservation observation,
                         Consumer<ExecutionObservation> durableWrite,
                         Consumer<ExecutionObservation> orderedProjection) {
        if (durableWrite != null) durableWrite.accept(observation);
        try {
            observe(observation);
        } catch (RuntimeException ignored) {
            // Optional listeners must not invalidate a completed durable projection.
        }
        if (orderedProjection != null) {
            try {
                orderedProjection.accept(observation);
            } catch (RuntimeException ignored) {
                // Optional projections are isolated after the durable write.
            }
        }
    }

    static ExecutionObserver noop() {
        return ignored -> { };
    }

    static ExecutionObserver composite(List<ExecutionObserver> observers) {
        return composite(observers, ignored -> { });
    }

    static ExecutionObserver composite(List<ExecutionObserver> observers,
                                       Consumer<RuntimeException> failureHandler) {
        List<ExecutionObserver> installed = observers != null
                ? observers.stream().filter(Objects::nonNull).toList() : List.of();
        Consumer<RuntimeException> failures = failureHandler != null ? failureHandler : ignored -> { };
        return new ExecutionObserver() {
            @Override
            public void observe(ExecutionObservation observation) {
                installed.forEach(observer -> notify(observer, observation));
            }

            @Override
            public void publish(ExecutionObservation observation,
                                Consumer<ExecutionObservation> durableWrite) {
                publish(observation, durableWrite, ignored -> { });
            }

            @Override
            public void publish(ExecutionObservation observation,
                                Consumer<ExecutionObservation> durableWrite,
                                Consumer<ExecutionObservation> orderedProjection) {
                ExecutionEventPublisher dispatcher = installed.stream()
                        .filter(ExecutionEventPublisher.class::isInstance)
                        .map(ExecutionEventPublisher.class::cast)
                        .findFirst().orElse(null);
                if (dispatcher != null) {
                    List<ExecutionObserver> legacy = installed.stream()
                            .filter(observer -> observer != dispatcher).toList();
                    dispatcher.publish(observation, durableWrite, event -> {
                        legacy.forEach(observer -> notify(observer, event));
                        if (orderedProjection != null) orderedProjection.accept(event);
                    });
                    return;
                }
                if (installed.size() == 1) {
                    installed.getFirst().publish(observation, durableWrite, orderedProjection);
                    return;
                }
                if (durableWrite != null) durableWrite.accept(observation);
                observe(observation);
                if (orderedProjection != null) orderedProjection.accept(observation);
            }

            private void notify(ExecutionObserver observer, ExecutionObservation observation) {
                try {
                    observer.observe(observation);
                } catch (RuntimeException failure) {
                    try {
                        failures.accept(failure);
                    } catch (RuntimeException ignored) {
                        // Diagnostics about an optional observer are optional too.
                    }
                }
            }
        };
    }
}
