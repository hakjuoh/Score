package org.oagi.score.gateway.http.api.activity_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityActor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreHttpRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreActivityEventSpanIdsTest {

    @Test
    void turnsDuplicateBatchSpanIdsIntoSiblingIdentities() {
        ScoreHttpRequest httpRequest = new ScoreHttpRequest(
                "POST", null, "https", "score.example.org", 443,
                "/api/core-components/acc", "page=1", "2", "203.0.113.10",
                "10.0.0.5", 51234, "Mozilla/5.0", "https://score.example.org",
                "https://score.example.org/core_component/acc", "3.6.0-dev",
                "Apache Tomcat/11.0", "forwarded",
                Map.of("forwarded", List.of("for=203.0.113.10"),
                        "x-forwarded-server", List.of("edge-proxy"),
                        "via", List.of("1.1 edge-proxy")));
        ScoreActivityContext shared = new ScoreActivityContext(
                "0123456789abcdef0123456789abcdef",
                "1111111111111111",
                "2222222222222222",
                "00", "vendor=state", "2026-08-06T12:00:00Z",
                "RELEASE_DRAFT", "request-1", "2026-08-06T11:59:59Z", httpRequest);

        List<ScoreActivityEvent> normalized = ScoreActivityEventSpanIds.unique(
                List.of(event("event-1", shared), event("event-2", shared), event("event-3", shared)));

        assertThat(normalized).extracting(event -> event.context().traceId())
                .containsOnly(shared.traceId());
        assertThat(normalized).extracting(event -> event.context().parentSpanId())
                .containsOnly(shared.parentSpanId());
        assertThat(normalized).extracting(event -> event.context().spanId())
                .doesNotHaveDuplicates();
        assertThat(normalized.getFirst().context().spanId()).isEqualTo(shared.spanId());
        assertThat(normalized).allSatisfy(event -> {
            ScoreActivityContext context = event.context();
            assertThat(context.traceFlags()).isEqualTo(shared.traceFlags());
            assertThat(context.traceState()).isEqualTo(shared.traceState());
            assertThat(context.activityStartedAt()).isEqualTo(shared.activityStartedAt());
            assertThat(context.requestType()).isEqualTo(shared.requestType());
            assertThat(context.requestId()).isEqualTo(shared.requestId());
            assertThat(context.requestTimestamp()).isEqualTo(shared.requestTimestamp());
            assertThat(context.httpRequest()).isSameAs(httpRequest);
        });
    }

    private static ScoreActivityEvent event(String id, ScoreActivityContext context) {
        return new ScoreActivityEvent(
                ScoreActivityEvent.SCHEMA_VERSION,
                id,
                Instant.parse("2026-08-06T12:00:01Z"),
                "acc.update",
                "SCORE_HTTP_API",
                ScoreActivityEvent.SUCCEEDED,
                new ScoreActivityActor("7", "developer"),
                List.of(),
                Map.of("requestedFields", List.of("definition")),
                context);
    }
}
