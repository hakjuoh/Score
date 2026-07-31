package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEventCanonicalizerTest {

    @Test
    void advancesTimestampAndCopiesOneIdentityIntoLifecycleMetadata() {
        ExecutionScope scope = new ExecutionScope(
                "request-1", "conversation-1", "user-1", 7,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ExecutionObservation source = new AiExecutionLifecycle(
                "tool", "tool_completed", "call-1", "lookup", 3L,
                Map.of("attempt", 2)).observation(scope);
        Instant previous = Instant.parse("2099-01-01T00:00:00.123456Z");

        ExecutionEventCanonicalizer.CanonicalEvent canonical =
                ExecutionEventCanonicalizer.canonicalize(source, 42L, previous);

        Instant expected = previous.plus(1, ChronoUnit.MICROS);
        assertThat(canonical.occurredAt()).isEqualTo(expected);
        assertThat(canonical.observation().occurredAt()).isEqualTo(expected);
        Map<String, Object> attributes = canonical.observation().attributes();
        assertThat(attributes.get(ExecutionEventPublisher.EVENT_ID)).isInstanceOf(String.class);
        assertThat(attributes.get(ExecutionEventPublisher.EVENT_SEQUENCE)).isEqualTo(42L);
        assertThat(attributes.get(ExecutionEventPublisher.EVENT_OCCURRED_AT))
                .isEqualTo(expected.toString());

        AiExecutionLifecycle lifecycle = AiExecutionLifecycle.from(canonical.observation())
                .orElseThrow();
        assertThat(lifecycle.metadata())
                .containsEntry(ExecutionEventPublisher.EVENT_ID,
                        attributes.get(ExecutionEventPublisher.EVENT_ID))
                .containsEntry(ExecutionEventPublisher.EVENT_SEQUENCE, 42L)
                .containsEntry(ExecutionEventPublisher.EVENT_OCCURRED_AT, expected.toString())
                .containsEntry("attempt", 2L);
    }
}
