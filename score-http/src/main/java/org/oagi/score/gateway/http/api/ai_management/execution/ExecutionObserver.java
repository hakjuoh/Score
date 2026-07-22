package org.oagi.score.gateway.http.api.ai_management.execution;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Passive observer; failures are isolated and cannot alter an Agent result. */
@FunctionalInterface
public interface ExecutionObserver {

    void observe(ExecutionObservation observation);

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
        return observation -> installed.forEach(observer -> {
            try {
                observer.observe(observation);
            } catch (RuntimeException failure) {
                try {
                    failures.accept(failure);
                } catch (RuntimeException ignored) {
                    // Diagnostics about an optional observer are optional too.
                }
            }
        });
    }
}
