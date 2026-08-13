package org.oagi.score.gateway.http.api.activity_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityActor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreHttpRequest;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenTelemetryScoreActivityEventSinkTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String RELEASE_SPAN_ID = "0123456789abcdef";
    private static final String ACC_SPAN_ID = "1111111111111111";

    @Test
    void exportsEventsAsExactReleaseToComponentSpanHierarchy() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Resource resource = Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "score"));
        try (OpenTelemetryScoreActivityEventSink sink = new OpenTelemetryScoreActivityEventSink(
                new ObjectMapper(), exporter, resource, Duration.ofSeconds(1))) {
            sink.write(event(
                    "release.state-change", ScoreActivityEvent.SUCCEEDED,
                    RELEASE_SPAN_ID, null, Map.of("toState", "Published")));
            sink.write(event(
                    "acc.state-change", ScoreActivityEvent.SUCCEEDED,
                    ACC_SPAN_ID, RELEASE_SPAN_ID,
                    Map.of("toState", "Published", "operation", "revise")));

            assertThat(exporter.getFinishedSpanItems()).hasSize(2);
            SpanData release = exporter.getFinishedSpanItems().get(0);
            SpanData acc = exporter.getFinishedSpanItems().get(1);
            assertThat(release.getName()).isEqualTo("score.activity release.state-change");
            assertThat(release.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(release.getSpanId()).isEqualTo(RELEASE_SPAN_ID);
            assertThat(release.getParentSpanId()).isEqualTo("0000000000000000");
            assertThat(acc.getTraceId()).isEqualTo(release.getTraceId());
            assertThat(acc.getSpanId()).isEqualTo(ACC_SPAN_ID);
            assertThat(acc.getParentSpanId()).isEqualTo(release.getSpanId());
            assertThat(acc.getStartEpochNanos()).isEqualTo(
                    epochNanos(Instant.parse("2026-08-06T11:59:58Z")));
            assertThat(acc.getEndEpochNanos()).isEqualTo(
                    epochNanos(Instant.parse("2026-08-06T12:00:00Z")));
            assertThat(acc.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
            assertThat(acc.getSpanContext().getTraceState().get("vendor")).isEqualTo("state");
            assertThat(acc.getSpanContext().getTraceFlags().isSampled()).isFalse();
            assertThat(acc.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                    .isEqualTo("score");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.activity.source")))
                    .isEqualTo("SCORE_HTTP_API");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.activity.operation")))
                    .isEqualTo("revise");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.request.id")))
                    .isEqualTo("ui-request-42");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.actor.user_id")))
                    .isEqualTo("7");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.actor.login_id")))
                    .isEqualTo("developer");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.target.id")))
                    .isEqualTo("42");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.target.type")))
                    .isEqualTo("ACC");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.target.guid")))
                    .isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.target.name")))
                    .isEqualTo("Invoice");
            assertThat(acc.getAttributes().asMap()).doesNotContainKeys(
                    AttributeKey.stringKey("score.activity.targets"),
                    AttributeKey.stringArrayKey("score.target.ids"),
                    AttributeKey.stringArrayKey("score.target.types"),
                    AttributeKey.stringKey("score.actor.user.id"),
                    AttributeKey.stringKey("score.actor.login.id"));
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("http.request.method")))
                    .isEqualTo("POST");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("url.path")))
                    .isEqualTo("/api/core-components/acc");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("server.address")))
                    .isEqualTo("score.example.org");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.ui.page_url")))
                    .isEqualTo("https://score.example.org/core_component/acc");
            assertThat(acc.getAttributes().get(AttributeKey.stringKey("score.web.version")))
                    .isEqualTo("3.6.0-dev");
            assertThat(acc.getAttributes().get(
                    AttributeKey.stringKey("score.http.server_software")))
                    .isEqualTo("Apache Tomcat/11.0");
            assertThat(acc.getAttributes().get(
                    AttributeKey.stringKey("score.http.client_address_source")))
                    .isEqualTo("forwarded");
            assertThat(acc.getAttributes().get(
                    AttributeKey.stringArrayKey("http.request.header.via")))
                    .containsExactly("1.1 edge-proxy");
        }
    }

    @Test
    void exportsFailureOutcomeAndErrorCode() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (OpenTelemetryScoreActivityEventSink sink = new OpenTelemetryScoreActivityEventSink(
                new ObjectMapper(), exporter, Resource.empty(), Duration.ofSeconds(1))) {
            sink.write(event(
                    "acc.update", ScoreActivityEvent.FAILED,
                    ACC_SPAN_ID, null, Map.of("errorCode", "VALIDATION_ERROR")));

            assertThat(exporter.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(span.getAttributes().get(
                        AttributeKey.stringKey("score.activity.error.code")))
                        .isEqualTo("VALIDATION_ERROR");
                assertThat(span.getAttributes().get(
                        AttributeKey.stringKey("score.activity.outcome")))
                        .isEqualTo(ScoreActivityEvent.FAILED);
            });
        }
    }

    @Test
    void keepsTheStructuredPluralTargetOnlyForActualMultiTargetEvents() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (OpenTelemetryScoreActivityEventSink sink = new OpenTelemetryScoreActivityEventSink(
                new ObjectMapper(), exporter, Resource.empty(), Duration.ofSeconds(1))) {
            ScoreActivityEvent base = event(
                    "acc.update", ScoreActivityEvent.SUCCEEDED,
                    ACC_SPAN_ID, null, Map.of());
            sink.write(new ScoreActivityEvent(
                    base.schemaVersion(), base.eventId(), base.occurredAt(), base.name(),
                    base.source(), base.outcome(), base.actor(),
                    List.of(
                            new ScoreActivityTarget("ACC", "42", "a".repeat(32), "Invoice", "PRIMARY"),
                            new ScoreActivityTarget("ACC", "43", "b".repeat(32), "Order", "RELATED")),
                    base.properties(), base.context()));

            assertThat(exporter.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getAttributes().get(
                        AttributeKey.stringKey("score.activity.targets")))
                        .contains("\"id\":\"42\"")
                        .contains("\"id\":\"43\"");
                assertThat(span.getAttributes().asMap()).doesNotContainKeys(
                        AttributeKey.stringKey("score.target.id"),
                        AttributeKey.stringKey("score.target.type"));
            });
        }
    }

    @Test
    void failureAwareProcessorReportsExporterFailure() {
        try (OpenTelemetryScoreActivityEventSink sink = new OpenTelemetryScoreActivityEventSink(
                new ObjectMapper(), new FailingSpanExporter(), Resource.empty(), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> sink.write(event(
                    "acc.update", ScoreActivityEvent.SUCCEEDED,
                    ACC_SPAN_ID, null, Map.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OTLP activity export failed");
        }
    }

    @Test
    void batchesABurstAndDrainsItWhenTheSinkCloses() {
        RecordingSpanExporter exporter = new RecordingSpanExporter();
        OpenTelemetryScoreActivityEventSink sink = new OpenTelemetryScoreActivityEventSink(
                new ObjectMapper(), exporter, Resource.empty(), Duration.ofSeconds(1),
                Duration.ofHours(1), 128);
        for (int index = 0; index < 100; index++) {
            String spanId = io.opentelemetry.sdk.trace.IdGenerator.random().generateSpanId();
            sink.write(event(
                    "acc.update", ScoreActivityEvent.SUCCEEDED,
                    spanId, null, Map.of("index", index)));
        }

        sink.close();

        assertThat(exporter.spans).hasSize(100);
        assertThat(exporter.exportCalls.get()).isLessThan(100);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SCORE_OTEL_COLLECTOR_IT", matches = "true")
    void exportsCompositeCoreComponentHierarchyThroughTheAsyncPublisherAndJaeger() throws Exception {
        String traceId = io.opentelemetry.sdk.trace.IdGenerator.random().generateTraceId();
        String bodSpanId = io.opentelemetry.sdk.trace.IdGenerator.random().generateSpanId();
        String accSpanId = io.opentelemetry.sdk.trace.IdGenerator.random().generateSpanId();
        String asccSpanId = io.opentelemetry.sdk.trace.IdGenerator.random().generateSpanId();
        Instant bodStartedAt = Instant.now().minusSeconds(1);
        String endpoint = System.getenv().getOrDefault(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://127.0.0.1:4318/v1/traces");
        try (OpenTelemetryScoreActivityEventSink sink = new OpenTelemetryScoreActivityEventSink(
                new ObjectMapper(), endpoint, Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofMillis(10), 16,
                Map.of(), Map.of("service.name", "score"));
             AsyncScoreActivityEventPublisher publisher = new AsyncScoreActivityEventPublisher(
                     sink, 1, 16, Duration.ofSeconds(2))) {
            publisher.publish(collectorEvent(
                    traceId, bodSpanId, null, "oagis-bod.create", "ASCCP",
                    bodStartedAt, bodStartedAt.plusMillis(900)));
            publisher.publish(collectorEvent(
                    traceId, accSpanId, bodSpanId, "acc.create", "ACC",
                    bodStartedAt.plusMillis(100), bodStartedAt.plusMillis(400)));
            publisher.publish(collectorEvent(
                    traceId, asccSpanId, bodSpanId, "ascc.create", "ASCC",
                    bodStartedAt.plusMillis(500), bodStartedAt.plusMillis(700)));
        }

        var trace = awaitJaegerTrace(traceId);
        var spans = trace.path("data").get(0).path("spans");
        List<com.fasterxml.jackson.databind.JsonNode> spanList = new ArrayList<>();
        spans.forEach(spanList::add);
        assertThat(spanList).extracting(span -> span.path("operationName").asText())
                .contains("score.activity oagis-bod.create", "score.activity acc.create",
                        "score.activity ascc.create");
        var component = spanList.stream()
                .filter(span -> span.path("operationName").asText()
                        .equals("score.activity ascc.create"))
                .findFirst()
                .orElseThrow();
        assertThat(component.path("references").get(0).path("spanID").asText())
                .isEqualTo(bodSpanId);
        String processId = component.path("processID").asText();
        assertThat(trace.path("data").get(0).path("processes").path(processId)
                .path("serviceName").asText()).isEqualTo("score");
        Map<String, String> tags = new java.util.HashMap<>();
        component.path("tags").forEach(tag -> tags.put(
                tag.path("key").asText(), tag.path("value").asText()));
        assertThat(tags)
                .containsEntry("score.actor.user_id", "7")
                .containsEntry("score.actor.login_id", "collector-it")
                .containsEntry("score.target.id", "42")
                .containsEntry("score.target.type", "ASCC")
                .containsEntry("score.activity.operation", "append")
                .containsEntry("http.request.method", "POST")
                .containsKey("http.request.header.x-forwarded-for")
                .containsKey("http.request.header.via")
                .containsEntry("url.path", "/api/core-components/acc")
                .containsEntry("score.web.version", "3.6.0-dev")
                .doesNotContainKeys("score.activity.targets", "score.target.ids", "score.target.types");
    }

    private static com.fasterxml.jackson.databind.JsonNode awaitJaegerTrace(String traceId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:16686/api/traces/" + traceId);
        for (int attempt = 0; attempt < 30; attempt++) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var json = new ObjectMapper().readTree(response.body());
                if (json.path("data").isArray() && !json.path("data").isEmpty()) {
                    return json;
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Jaeger did not store trace " + traceId);
    }

    private static ScoreActivityEvent collectorEvent(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            String targetType,
            Instant startedAt,
            Instant occurredAt) {
        return new ScoreActivityEvent(
                ScoreActivityEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                occurredAt,
                name,
                "SCORE_HTTP_API",
                ScoreActivityEvent.SUCCEEDED,
                new ScoreActivityActor("7", "collector-it"),
                List.of(new ScoreActivityTarget(
                        targetType, "42", "0123456789abcdef0123456789abcdef", name, "PRIMARY")),
                Map.of("operation", switch (name) {
                    case "oagis-bod.create" -> "generate-bod";
                    case "ascc.create" -> "append";
                    default -> "create";
                }),
                new ScoreActivityContext(
                        traceId, spanId, parentSpanId, "00", "", startedAt.toString(),
                        "OAGIS_BOD_CREATE", UUID.randomUUID().toString(), startedAt.toString(),
                        new ScoreHttpRequest(
                                "POST", null, "http", "127.0.0.1", 8080,
                                "/api/core-components/acc", null, "1.1", "203.0.113.10",
                                "127.0.0.1", 51234, "collector-integration-test",
                                "http://127.0.0.1:4200", "http://127.0.0.1:4200/core_component/acc",
                                "3.6.0-dev", "Apache Tomcat/11.0",
                                "x-forwarded-for", Map.of(
                                        "x-forwarded-for", List.of("203.0.113.10, 127.0.0.1"),
                                        "via", List.of("1.1 collector-integration-proxy")))));
    }

    private static ScoreActivityEvent event(
            String name,
            String outcome,
            String spanId,
            String parentSpanId,
            Map<String, Object> properties) {
        return new ScoreActivityEvent(
                ScoreActivityEvent.SCHEMA_VERSION,
                "cc2f52bb-cffa-42c6-b673-6e25aba5ebda",
                Instant.parse("2026-08-06T12:00:00Z"),
                name,
                "SCORE_HTTP_API",
                outcome,
                new ScoreActivityActor("7", "developer"),
                List.of(new ScoreActivityTarget(
                        "ACC", "42", "0123456789abcdef0123456789abcdef", "Invoice", "PRIMARY")),
                properties,
                new ScoreActivityContext(
                        TRACE_ID, spanId, parentSpanId, "00", "vendor=state",
                        "2026-08-06T11:59:58Z",
                        "RELEASE_PUBLISH", "ui-request-42", "2026-08-06T11:59:59Z",
                        new ScoreHttpRequest(
                                "POST", null, "https", "score.example.org", 443,
                                "/api/core-components/acc", null, "2", "203.0.113.10",
                                "10.0.0.5", 51234, "Mozilla/5.0",
                                "https://score.example.org",
                                "https://score.example.org/core_component/acc",
                                "3.6.0-dev", "Apache Tomcat/11.0",
                                "forwarded",
                                Map.of(
                                        "forwarded", List.of("for=203.0.113.10;proto=https"),
                                        "x-forwarded-server", List.of("edge-proxy"),
                                        "via", List.of("1.1 edge-proxy")))));
    }

    private static long epochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private static final class FailingSpanExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofFailure();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class RecordingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();
        private final AtomicInteger exportCalls = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exportCalls.incrementAndGet();
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
