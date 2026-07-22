package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionObserverTest {

    @Test
    void emptyAndFailingOptionalObserversNeverChangeExecution() {
        ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AtomicBoolean secondObserved = new AtomicBoolean();
        AtomicBoolean failureIsolated = new AtomicBoolean();
        ExecutionObserver composite = ExecutionObserver.composite(List.of(
                ignored -> { throw new IllegalStateException("optional sink failed"); },
                ignored -> secondObserved.set(true)), ignored -> failureIsolated.set(true));

        composite.observe(ExecutionObservation.of("agent.completed", scope, Map.of()));
        ExecutionObserver.composite(List.of()).observe(
                ExecutionObservation.of("agent.completed", scope, Map.of()));

        assertThat(secondObserved).isTrue();
        assertThat(failureIsolated).isTrue();
    }
}
