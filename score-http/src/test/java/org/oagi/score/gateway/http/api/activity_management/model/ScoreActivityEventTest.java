package org.oagi.score.gateway.http.api.activity_management.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreActivityEventTest {

    @Test
    void deeplyCopiesJsonProperties() {
        List<String> fields = new ArrayList<>(List.of("definition"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestedFields", fields);

        ScoreActivityEvent event = event(properties);
        fields.add("namespaceId");
        properties.put("later", true);

        assertThat(event.properties()).containsOnlyKeys("requestedFields");
        assertThat(event.properties().get("requestedFields"))
                .isEqualTo(List.of("definition"));
    }

    @Test
    void rejectsValuesThatCannotHaveTheSameMeaningInOtherRuntimes() {
        assertThatThrownBy(() -> event(Map.of("value", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-compatible");
    }

    private static ScoreActivityEvent event(Map<String, Object> properties) {
        return new ScoreActivityEvent(
                "1.0",
                "cc2f52bb-cffa-42c6-b673-6e25aba5ebda",
                Instant.parse("2026-08-06T12:00:00Z"),
                "acc.update",
                "SCORE_HTTP_API",
                "SUCCEEDED",
                new ScoreActivityActor("7", "developer"),
                List.of(new ScoreActivityTarget("ACC", "42", null, null, "PRIMARY")),
                properties,
                ScoreActivityContext.empty());
    }
}
