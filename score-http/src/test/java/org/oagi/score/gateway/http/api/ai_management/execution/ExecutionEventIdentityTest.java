package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ExecutionEventIdentityTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-07-30T12:34:56Z");

    @Test
    void extractsAndProjectsTheCanonicalWireIdentity() {
        ExecutionObservation observation = observation(Map.of(
                ExecutionEventPublisher.EVENT_ID, "request-1:2",
                ExecutionEventPublisher.EVENT_SEQUENCE, 2L,
                ExecutionEventPublisher.EVENT_OCCURRED_AT, OCCURRED_AT.toString()));

        ExecutionEventIdentity identity = ExecutionEventIdentity.find(observation).orElseThrow();
        Map<String, Object> target = new LinkedHashMap<>(Map.of(
                ExecutionEventPublisher.EVENT_ID, "stale"));
        identity.putAttributes(target);

        assertThat(identity).isEqualTo(new ExecutionEventIdentity("request-1:2", 2L, OCCURRED_AT));
        assertThat(target).containsEntry(ExecutionEventPublisher.EVENT_ID, "request-1:2")
                .containsEntry(ExecutionEventPublisher.EVENT_SEQUENCE, 2L)
                .containsEntry(ExecutionEventPublisher.EVENT_OCCURRED_AT, OCCURRED_AT.toString());
    }

    @Test
    void rejectsIncompleteOrMalformedIdentityAttributes() {
        assertThat(ExecutionEventIdentity.find(null)).isEmpty();
        assertThat(ExecutionEventIdentity.find(observation(Map.of()))).isEmpty();
        assertThat(ExecutionEventIdentity.find(observation(Map.of(
                ExecutionEventPublisher.EVENT_ID, "event",
                ExecutionEventPublisher.EVENT_SEQUENCE, "one")))).isEmpty();
        assertThat(ExecutionEventIdentity.find(observation(Map.of(
                ExecutionEventPublisher.EVENT_ID, " ",
                ExecutionEventPublisher.EVENT_SEQUENCE, 1L)))).isEmpty();
        assertThat(ExecutionEventIdentity.find(observation(Map.of(
                ExecutionEventPublisher.EVENT_ID, "event",
                ExecutionEventPublisher.EVENT_SEQUENCE, 0L)))).isEmpty();
    }

    @Test
    void enforcesCanonicalIdentityInvariants() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExecutionEventIdentity(" ", 1L, OCCURRED_AT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExecutionEventIdentity("event", 0L, OCCURRED_AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new ExecutionEventIdentity("event", 1L, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ExecutionEventIdentity("event", 1L, OCCURRED_AT)
                        .putAttributes(null));
    }

    @Test
    void copiesOnlyPresentCanonicalAttributesAndOverwritesStaleValues() {
        Map<String, Object> target = new LinkedHashMap<>(Map.of(
                ExecutionEventPublisher.EVENT_ID, "stale",
                "domain", "kept"));

        ExecutionEventIdentity.copyAttributes(Map.of(
                ExecutionEventPublisher.EVENT_ID, "event",
                ExecutionEventPublisher.EVENT_SEQUENCE, 3L), target);

        assertThat(target).containsEntry(ExecutionEventPublisher.EVENT_ID, "event")
                .containsEntry(ExecutionEventPublisher.EVENT_SEQUENCE, 3L)
                .containsEntry("domain", "kept")
                .doesNotContainKey(ExecutionEventPublisher.EVENT_OCCURRED_AT);
    }

    private static ExecutionObservation observation(Map<String, Object> attributes) {
        return new ExecutionObservation("test.event",
                new ExecutionScope("request-1", "conversation-1", "user-1", 0L,
                        ExecutionScope.Purpose.USER_RESPONSE, List.of()),
                OCCURRED_AT, attributes);
    }
}
