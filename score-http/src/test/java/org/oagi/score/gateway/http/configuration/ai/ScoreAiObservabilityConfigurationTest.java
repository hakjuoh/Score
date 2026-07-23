package org.oagi.score.gateway.http.configuration.ai;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreAiObservabilityConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ScoreAiObservabilityConfiguration.class);

    @Test
    void devProfileUsesOtelEnvironmentNamesForSharedManagementSettings() throws IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/application-dev.yml"))) {
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml)
                    .doesNotContain("SCORE_OTEL_")
                    .contains("${OTEL_TRACES_SAMPLER_ARG:1.0}")
                    .contains("${OTEL_TRACES_EXPORT_ENABLED:true}")
                    .contains("${OTEL_TRACES_SAMPLER:parentbased_traceidratio}")
                    .contains("${OTEL_BSP_SCHEDULE_DELAY:5s}")
                    .contains("${OTEL_BSP_EXPORT_TIMEOUT:10s}")
                    .contains("${OTEL_METRIC_EXPORT_INTERVAL:10s}")
                    .contains("${SCORE_AI_OBSERVABILITY_ENABLED:true}")
                    .contains("${SCORE_AI_OBSERVABILITY_SERVICE_NAME:score-ai}");
        }
    }

    @Test
    void defaultsToTheAiServiceAndDoesNotPublishAGlobalSdkBean() {
        context.withPropertyValues("score.ai.observability.enabled=false")
                .run(application -> {
                    assertThat(application).hasSingleBean(
                            ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class);
                    assertThat(application).doesNotHaveBean(OpenTelemetry.class);
                    ScoreAiObservabilityProperties properties = application.getBean(
                            ScoreAiObservabilityProperties.class);
                    assertThat(properties.getServiceName()).isEqualTo("score-ai");
                });
    }

    @Test
    void managementDefaultsPreserveThePrivateAiSdkExportPolicyWithoutAProfile() {
        context.withPropertyValues("score.ai.observability.enabled=false")
                .run(application -> {
                    ScoreAiObservabilityManagementProperties management = application.getBean(
                            ScoreAiObservabilityManagementProperties.class);
                    assertThat(management.getTracing().getSampling().getProbability()).isEqualTo(1.0);
                    assertThat(management.getTracing().getExport().isEnabled()).isTrue();
                    assertThat(management.getTracing().getExport().getOtlp().isEnabled()).isTrue();
                    assertThat(management.getOpentelemetry().getResourceAttributes())
                            .containsEntry("service.namespace", "oagi.score")
                            .containsEntry("deployment.environment.name", "unknown");
                    assertThat(management.getOpentelemetry().getTracing().getSampler())
                            .isEqualTo(ScoreAiObservabilityManagementProperties.Sampler
                                    .PARENT_BASED_TRACE_ID_RATIO);
                    assertThat(management.getOpentelemetry().getTracing().getExport()
                            .getScheduleDelay()).hasSeconds(5);
                    assertThat(management.getOpentelemetry().getTracing().getExport()
                            .getTimeout()).hasSeconds(10);
                    assertThat(management.getOpentelemetry().getTracing().getExport().getOtlp()
                            .getTimeout()).hasSeconds(10);
                    assertThat(management.getOpentelemetry().getTracing().getExport().getOtlp()
                            .getConnectTimeout()).hasSeconds(10);
                    assertThat(management.getOtlp().getMetrics().getExport().isEnabled()).isTrue();
                    assertThat(management.getOtlp().getMetrics().getExport().getStep())
                            .hasSeconds(10);
                    assertThat(management.getOtlp().getMetrics().getExport().getReadTimeout())
                            .hasSeconds(10);
                });
    }

    @Test
    void canonicalOtelSamplerNameBindsToTheSharedManagementProperty() {
        context.withPropertyValues(
                        "score.ai.observability.enabled=false",
                        "management.opentelemetry.tracing.sampler=parentbased_traceidratio")
                .run(application -> {
                    ScoreAiObservabilityManagementProperties management = application.getBean(
                            ScoreAiObservabilityManagementProperties.class);
                    assertThat(management.getOpentelemetry().getTracing().getSampler())
                            .isEqualTo(ScoreAiObservabilityManagementProperties.Sampler
                                    .PARENT_BASED_TRACE_ID_RATIO);
                });
    }

    @Test
    void sharedManagementSwitchCanDisableThePrivateAiSdk() {
        context.withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.opentelemetry.enabled=false")
                .run(application -> {
                    var sdk = application.getBean(
                            ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class);
                    assertThat(sdk.openTelemetry()).isSameAs(OpenTelemetry.noop());
                    assertThat(application).doesNotHaveBean(OpenTelemetry.class);
                });
    }

    @Test
    void reusesStandardManagementPropertiesForSharedOtelSettings() {
        context.withPropertyValues(
                        "score.ai.observability.enabled=false",
                        "score.ai.observability.service-name=custom-ai",
                        "management.tracing.sampling.probability=0.25",
                        "management.tracing.export.enabled=false",
                        "management.tracing.export.otlp.enabled=false",
                        "management.opentelemetry.resource-attributes.service.namespace=test.namespace",
                        "management.opentelemetry.tracing.sampler=always_off",
                        "management.opentelemetry.tracing.export.schedule-delay=3s",
                        "management.opentelemetry.tracing.export.timeout=13s",
                        "management.opentelemetry.tracing.export.otlp.endpoint=http://collector:4318/v1/traces",
                        "management.opentelemetry.tracing.export.otlp.timeout=11s",
                        "management.opentelemetry.tracing.export.otlp.connect-timeout=9s",
                        "management.opentelemetry.tracing.export.otlp.headers.authorization=trace-token",
                        "management.otlp.metrics.export.url=http://collector:4318/v1/metrics",
                        "management.otlp.metrics.export.step=7s",
                        "management.otlp.metrics.export.connect-timeout=8s",
                        "management.otlp.metrics.export.read-timeout=12s",
                        "management.otlp.metrics.export.headers.authorization=metric-token")
                .run(application -> {
                    ScoreAiObservabilityProperties properties = application.getBean(
                            ScoreAiObservabilityProperties.class);
                    assertThat(properties.getServiceName()).isEqualTo("custom-ai");
                    ScoreAiObservabilityManagementProperties management = application.getBean(
                            ScoreAiObservabilityManagementProperties.class);
                    assertThat(management.getTracing().getSampling().getProbability()).isEqualTo(0.25);
                    assertThat(management.getTracing().getExport().isEnabled()).isFalse();
                    assertThat(management.getTracing().getExport().getOtlp().isEnabled()).isFalse();
                    assertThat(management.getOpentelemetry().getResourceAttributes())
                            .containsEntry("service.namespace", "test.namespace");
                    assertThat(management.getOpentelemetry().getTracing().getSampler())
                            .isEqualTo(ScoreAiObservabilityManagementProperties.Sampler.ALWAYS_OFF);
                    assertThat(management.getOpentelemetry().getTracing().getExport()
                            .getScheduleDelay()).hasSeconds(3);
                    assertThat(management.getOpentelemetry().getTracing().getExport()
                            .getTimeout()).hasSeconds(13);
                    assertThat(management.getOpentelemetry().getTracing().getExport().getOtlp()
                            .getEndpoint())
                            .isEqualTo("http://collector:4318/v1/traces");
                    assertThat(management.getOpentelemetry().getTracing().getExport().getOtlp()
                            .getTimeout()).hasSeconds(11);
                    assertThat(management.getOpentelemetry().getTracing().getExport().getOtlp()
                            .getConnectTimeout()).hasSeconds(9);
                    assertThat(management.getOpentelemetry().getTracing().getExport().getOtlp()
                            .getHeaders()).containsEntry("authorization", "trace-token");
                    assertThat(management.getOtlp().getMetrics().getExport().getUrl())
                            .isEqualTo("http://collector:4318/v1/metrics");
                    assertThat(management.getOtlp().getMetrics().getExport().getStep()).hasSeconds(7);
                    assertThat(management.getOtlp().getMetrics().getExport().getConnectTimeout())
                            .hasSeconds(8);
                    assertThat(management.getOtlp().getMetrics().getExport().getReadTimeout())
                            .hasSeconds(12);
                    assertThat(management.getOtlp().getMetrics().getExport().getHeaders())
                            .containsEntry("authorization", "metric-token");
                });
    }

    @Test
    void enabledSdkExportsItsOwnServiceIdentityHeadersSpansAndMetricsWithoutReplacingGlobalState()
            throws IOException {
        OpenTelemetry globalBefore = GlobalOpenTelemetry.get();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger traceRequests = new AtomicInteger();
        AtomicInteger metricRequests = new AtomicInteger();
        AtomicReference<byte[]> tracePayload = new AtomicReference<>();
        AtomicReference<byte[]> metricPayload = new AtomicReference<>();
        AtomicReference<String> traceHeader = new AtomicReference<>();
        AtomicReference<String> metricHeader = new AtomicReference<>();
        collector.createContext("/v1/traces", exchange -> {
            traceHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Trace"));
            tracePayload.set(exchange.getRequestBody().readAllBytes());
            traceRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.createContext("/v1/metrics", exchange -> {
            metricHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Metric"));
            metricPayload.set(exchange.getRequestBody().readAllBytes());
            metricRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
        try {
            String endpoint = "http://127.0.0.1:" + collector.getAddress().getPort();
            context.withPropertyValues(
                            "score.ai.observability.enabled=true",
                            "management.opentelemetry.resource-attributes.service.name=wrong-global-name",
                            "management.opentelemetry.tracing.sampler=always_on",
                            "management.opentelemetry.tracing.export.otlp.endpoint="
                                    + endpoint + "/v1/traces",
                            "management.opentelemetry.tracing.export.otlp.headers.x-test-trace=trace-token",
                            "management.opentelemetry.tracing.export.otlp.timeout=1s",
                            "management.opentelemetry.tracing.export.timeout=1s",
                            "management.opentelemetry.tracing.export.schedule-delay=1h",
                            "management.otlp.metrics.export.url=" + endpoint + "/v1/metrics",
                            "management.otlp.metrics.export.headers.x-test-metric=metric-token",
                            "management.otlp.metrics.export.step=1h",
                            "management.otlp.metrics.export.read-timeout=1s")
                    .run(application -> {
                        var sdk = application.getBean(
                                ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class);
                        assertThat(sdk.serviceName()).isEqualTo("score-ai");
                        assertThat(sdk.openTelemetry()).isNotSameAs(globalBefore);
                        assertThat(GlobalOpenTelemetry.get()).isSameAs(globalBefore);
                        assertThat(application).doesNotHaveBean(OpenTelemetry.class);
                        sdk.openTelemetry().getTracer("test").spanBuilder("ai-test").startSpan().end();
                        sdk.openTelemetry().getMeter("test").counterBuilder("score.ai.test")
                                .build().add(1);
                        OpenTelemetrySdk privateSdk = (OpenTelemetrySdk) sdk.openTelemetry();
                        assertThat(privateSdk.getSdkTracerProvider().forceFlush()
                                .join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
                        assertThat(privateSdk.getSdkMeterProvider().forceFlush()
                                .join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
                    });
            assertThat(traceRequests.get()).isEqualTo(1);
            assertThat(metricRequests.get()).isGreaterThanOrEqualTo(1);
            assertThat(traceHeader.get()).isEqualTo("trace-token");
            assertThat(metricHeader.get()).isEqualTo("metric-token");
            String traceWire = new String(tracePayload.get(), StandardCharsets.ISO_8859_1);
            String metricWire = new String(metricPayload.get(), StandardCharsets.ISO_8859_1);
            assertThat(traceWire).contains("score-ai").doesNotContain("wrong-global-name");
            assertThat(metricWire).contains("score-ai").doesNotContain("wrong-global-name");
        } finally {
            collector.stop(0);
        }
    }

    @Test
    void standardTraceExportSwitchAndSamplerAreAppliedToThePrivateSdk() {
        context.withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.tracing.export.enabled=false",
                        "management.opentelemetry.tracing.sampler=always_off",
                        "management.otlp.metrics.export.enabled=false")
                .run(application -> {
                    var sdk = application.getBean(
                            ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class);
                    var span = sdk.openTelemetry().getTracer("test")
                            .spanBuilder("not-sampled").startSpan();
                    assertThat(span.isRecording()).isFalse();
                    assertThat(span.getSpanContext().isSampled()).isFalse();
                    span.end();
                });
    }

    @Test
    void eachStandardExportSwitchSuppressesItsOwnCollectorPost() throws IOException {
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger traceRequests = new AtomicInteger();
        AtomicInteger metricRequests = new AtomicInteger();
        collector.createContext("/v1/traces", exchange -> {
            traceRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.createContext("/v1/metrics", exchange -> {
            metricRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
        try {
            String endpoint = "http://127.0.0.1:" + collector.getAddress().getPort();
            assertNoCollectorPost(endpoint, "management.tracing.export.enabled=false", true);
            assertNoCollectorPost(endpoint, "management.tracing.export.otlp.enabled=false", true);
            assertNoCollectorPost(endpoint, "management.otlp.metrics.export.enabled=false", false);
            assertThat(traceRequests.get()).isZero();
            assertThat(metricRequests.get()).isZero();
        } finally {
            collector.stop(0);
        }
    }

    @Test
    void ratioAndParentBasedSamplerMappingsFollowStandardProbabilityAndParentFlags() {
        context.withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.tracing.export.enabled=false",
                        "management.otlp.metrics.export.enabled=false",
                        "management.opentelemetry.tracing.sampler=trace_id_ratio",
                        "management.tracing.sampling.probability=0")
                .run(application -> {
                    OpenTelemetry openTelemetry = application.getBean(
                                    ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class)
                            .openTelemetry();
                    assertThat(sampled(openTelemetry, "ratio-zero")).isFalse();
                });
        context.withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.tracing.export.enabled=false",
                        "management.otlp.metrics.export.enabled=false",
                        "management.opentelemetry.tracing.sampler=trace_id_ratio",
                        "management.tracing.sampling.probability=1")
                .run(application -> {
                    OpenTelemetry openTelemetry = application.getBean(
                                    ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class)
                            .openTelemetry();
                    assertThat(sampled(openTelemetry, "ratio-one")).isTrue();
                });
        context.withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.tracing.export.enabled=false",
                        "management.otlp.metrics.export.enabled=false",
                        "management.opentelemetry.tracing.sampler=parent_based_always_off")
                .run(application -> {
                    var tracer = application.getBean(
                                    ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class)
                            .openTelemetry().getTracer("test");
                    Span root = tracer.spanBuilder("root-off").startSpan();
                    assertThat(root.getSpanContext().isSampled()).isFalse();
                    SpanContext sampledParent = SpanContext.createFromRemoteParent(
                            "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                            TraceFlags.getSampled(), TraceState.getDefault());
                    Span child = tracer.spanBuilder("sampled-child")
                            .setParent(Context.root().with(Span.wrap(sampledParent))).startSpan();
                    assertThat(child.getSpanContext().isSampled()).isTrue();
                    child.end();
                    root.end();
                });
    }

    @Test
    void privateAiSdkCoexistsWithAnIndependentApplicationOpenTelemetryBean() {
        OpenTelemetry applicationSdk = OpenTelemetry.noop();
        context.withBean("applicationOpenTelemetry", OpenTelemetry.class, () -> applicationSdk)
                .withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.tracing.export.enabled=false",
                        "management.otlp.metrics.export.enabled=false")
                .run(application -> {
                    var aiSdk = application.getBean(
                            ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class);
                    assertThat(application.getBean(OpenTelemetry.class)).isSameAs(applicationSdk);
                    assertThat(aiSdk.openTelemetry()).isNotSameAs(applicationSdk);
                });
    }

    private void assertNoCollectorPost(String endpoint, String disabledProperty,
                                       boolean emitTrace) {
        context.withPropertyValues(
                        "score.ai.observability.enabled=true",
                        "management.opentelemetry.tracing.sampler=always_on",
                        "management.opentelemetry.tracing.export.otlp.endpoint="
                                + endpoint + "/v1/traces",
                        "management.otlp.metrics.export.url=" + endpoint + "/v1/metrics",
                        emitTrace ? "management.otlp.metrics.export.enabled=false"
                                : "management.tracing.export.enabled=false",
                        disabledProperty)
                .run(application -> {
                    OpenTelemetrySdk sdk = (OpenTelemetrySdk) application.getBean(
                                    ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk.class)
                            .openTelemetry();
                    if (emitTrace) {
                        sdk.getTracer("test").spanBuilder("disabled-trace").startSpan().end();
                        assertThat(sdk.getSdkTracerProvider().forceFlush()
                                .join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
                    } else {
                        sdk.getMeter("test").counterBuilder("disabled.metric").build().add(1);
                        assertThat(sdk.getSdkMeterProvider().forceFlush()
                                .join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
                    }
                });
    }

    private boolean sampled(OpenTelemetry openTelemetry, String spanName) {
        Span span = openTelemetry.getTracer("test").spanBuilder(spanName).startSpan();
        try {
            return span.getSpanContext().isSampled();
        } finally {
            span.end();
        }
    }
}
