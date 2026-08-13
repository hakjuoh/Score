package org.oagi.score.gateway.http.api.activity_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.opentelemetry.sdk.trace.data.LinkData;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreHttpRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/** Converts activity events to OTLP spans on the asynchronous publisher worker. */
public final class OpenTelemetryScoreActivityEventSink implements ScoreActivityEventSink, AutoCloseable {

    public static final String TYPE = "opentelemetry";
    private static final String INSTRUMENTATION_SCOPE = "org.oagi.score.activity.events";
    private static final IdGenerator RANDOM_IDS = IdGenerator.random();
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private final ObjectMapper objectMapper;
    private final ForcedEventIdGenerator idGenerator;
    private final SynchronousExportSpanProcessor failureAwareSpanProcessor;
    private final SdkTracerProvider tracerProvider;
    private final io.opentelemetry.api.trace.Tracer tracer;

    public OpenTelemetryScoreActivityEventSink(
            ObjectMapper objectMapper,
            String endpoint,
            Duration connectTimeout,
            Duration exportTimeout,
            Duration scheduleDelay,
            int queueCapacity,
            Map<String, String> headers,
            Map<String, String> resourceAttributes) {
        this(
                objectMapper,
                exporter(endpoint, connectTimeout, exportTimeout, headers),
                resource(resourceAttributes),
                exportTimeout,
                scheduleDelay,
                queueCapacity);
    }

    OpenTelemetryScoreActivityEventSink(
            ObjectMapper objectMapper,
            SpanExporter exporter,
            Resource resource,
            Duration exportTimeout) {
        this(objectMapper, exporter, resource, exportTimeout, null, 0);
    }

    OpenTelemetryScoreActivityEventSink(
            ObjectMapper objectMapper,
            SpanExporter exporter,
            Resource resource,
            Duration exportTimeout,
            Duration scheduleDelay,
            int queueCapacity) {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null").copy();
        this.idGenerator = new ForcedEventIdGenerator();
        SpanProcessor spanProcessor;
        if (scheduleDelay == null) {
            this.failureAwareSpanProcessor = new SynchronousExportSpanProcessor(exporter, exportTimeout);
            spanProcessor = failureAwareSpanProcessor;
        } else {
            this.failureAwareSpanProcessor = null;
            int maxQueueSize = positive(queueCapacity, "queueCapacity");
            spanProcessor = BatchSpanProcessor.builder(exporter)
                    .setExportUnsampledSpans(true)
                    .setScheduleDelay(positive(scheduleDelay, "scheduleDelay"))
                    .setExporterTimeout(positive(exportTimeout, "exportTimeout"))
                    .setMaxQueueSize(maxQueueSize)
                    .setMaxExportBatchSize(Math.min(512, maxQueueSize))
                    .build();
        }
        this.tracerProvider = SdkTracerProvider.builder()
                .setIdGenerator(idGenerator)
                .setSampler(new EventTraceFlagsSampler(idGenerator))
                .setResource(requireNonNull(resource, "resource must not be null"))
                .addSpanProcessor(spanProcessor)
                .build();
        this.tracer = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()
                .getTracer(INSTRUMENTATION_SCOPE);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void write(ScoreActivityEvent event) {
        requireNonNull(event, "event must not be null");
        ScoreActivityContext activityContext = event.context();
        ForcedIds forcedIds = ForcedIds.from(activityContext);
        idGenerator.use(forcedIds);
        clearFailure();
        try {
            Instant startedAt = startTimestamp(activityContext, event.occurredAt());
            var builder = tracer.spanBuilder("score.activity " + event.name())
                    .setSpanKind(SpanKind.INTERNAL)
                    .setStartTimestamp(startedAt)
                    .setAllAttributes(attributes(event));
            Context parent = parent(activityContext);
            if (parent == null) {
                builder.setNoParent();
            } else {
                builder.setParent(parent);
            }
            Span span = builder.startSpan();
            span.setStatus(ScoreActivityEvent.SUCCEEDED.equals(event.outcome())
                    ? StatusCode.OK : StatusCode.ERROR);
            span.end(endTimestamp(startedAt, event.occurredAt()));
            throwIfFailed();
        } finally {
            idGenerator.clear();
            clearFailure();
        }
    }

    private Attributes attributes(ScoreActivityEvent event) {
        var attributes = Attributes.builder()
                .put("score.activity.event_id", event.eventId())
                .put("score.activity.schema_version", event.schemaVersion())
                .put("score.activity.name", event.name())
                .put("score.activity.source", event.source())
                .put("score.activity.outcome", event.outcome())
                .put("score.actor.user_id", event.actor().userId())
                .put("score.actor.login_id", event.actor().loginId())
                .put("score.activity.properties", json(event.properties()));
        putTargets(attributes, event.targets());
        ScoreActivityContext context = event.context();
        putIfPresent(attributes, "score.request.type", context.requestType());
        putIfPresent(attributes, "score.request.id", context.requestId());
        putIfPresent(attributes, "score.request.timestamp", context.requestTimestamp());
        putHttpRequest(attributes, context.httpRequest());
        Object errorCode = event.properties().get("errorCode");
        if (errorCode != null) {
            attributes.put("score.activity.error.code", errorCode.toString());
        }
        Object operation = event.properties().get("operation");
        if (operation != null) {
            attributes.put("score.activity.operation", operation.toString());
        }
        return attributes.build();
    }

    private void putTargets(
            io.opentelemetry.api.common.AttributesBuilder attributes,
            List<ScoreActivityTarget> targets) {
        if (targets.size() == 1) {
            ScoreActivityTarget target = targets.getFirst();
            attributes.put("score.target.type", target.type());
            attributes.put("score.target.id", target.id());
            attributes.put("score.target.role", target.role());
            putIfPresent(attributes, "score.target.guid", target.guid());
            putIfPresent(attributes, "score.target.name", target.name());
        } else if (!targets.isEmpty()) {
            attributes.put("score.activity.targets", json(targets));
        }
    }

    private static void putHttpRequest(
            io.opentelemetry.api.common.AttributesBuilder attributes,
            ScoreHttpRequest request) {
        if (request == null) {
            return;
        }
        putIfPresent(attributes, "http.request.method", request.method());
        putIfPresent(attributes, "http.request.method_original", request.originalMethod());
        putIfPresent(attributes, "url.scheme", request.scheme());
        putIfPresent(attributes, "url.path", request.path());
        putIfPresent(attributes, "url.query", request.query());
        putIfPresent(attributes, "server.address", request.serverAddress());
        putIfPresent(attributes, "server.port", request.serverPort());
        putIfPresent(attributes, "network.protocol.version", request.protocolVersion());
        putIfPresent(attributes, "client.address", request.clientAddress());
        putIfPresent(attributes, "network.peer.address", request.networkPeerAddress());
        putIfPresent(attributes, "network.peer.port", request.networkPeerPort());
        putIfPresent(attributes, "user_agent.original", request.userAgent());
        putIfPresent(attributes, "score.ui.origin", request.uiOrigin());
        putIfPresent(attributes, "score.ui.page_url", request.uiPageUrl());
        putIfPresent(attributes, "score.web.version", request.webVersion());
        putIfPresent(attributes, "score.http.server_software", request.serverSoftware());
        putIfPresent(attributes, "score.http.client_address_source", request.clientAddressSource());
        request.proxyHeaders().forEach((name, values) -> attributes.put(
                AttributeKey.stringArrayKey("http.request.header." + name), values));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SCORE activity event cannot be serialized", exception);
        }
    }

    private static Context parent(ScoreActivityContext context) {
        if (context == null
                || !TraceId.isValid(context.traceId())
                || !SpanId.isValid(context.parentSpanId())) {
            return null;
        }
        String flags = context.traceFlags() != null ? context.traceFlags() : "01";
        Map<String, String> carrier = new LinkedHashMap<>();
        carrier.put("traceparent", "00-" + context.traceId() + '-' + context.parentSpanId() + '-' + flags);
        if (context.traceState() != null && !context.traceState().isBlank()) {
            carrier.put("tracestate", context.traceState());
        }
        Context parent = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), carrier, MAP_GETTER);
        return Span.fromContext(parent).getSpanContext().isValid() ? parent : null;
    }

    private static Instant startTimestamp(ScoreActivityContext context, Instant fallback) {
        if (context != null && context.activityStartedAt() != null) {
            try {
                return Instant.parse(context.activityStartedAt());
            } catch (DateTimeParseException ignored) {
                // The event remains exportable even if an external producer sent malformed optional context.
            }
        }
        return fallback;
    }

    private static Instant endTimestamp(Instant startedAt, Instant occurredAt) {
        if (occurredAt.isAfter(startedAt)) {
            return occurredAt;
        }
        return startedAt.equals(Instant.MAX) ? startedAt : startedAt.plusNanos(1);
    }

    private static void putIfPresent(
            io.opentelemetry.api.common.AttributesBuilder attributes,
            String key,
            String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private static void putIfPresent(
            io.opentelemetry.api.common.AttributesBuilder attributes,
            String key,
            Integer value) {
        if (value != null) {
            attributes.put(key, value.longValue());
        }
    }

    private static OtlpHttpSpanExporter exporter(
            String endpoint,
            Duration connectTimeout,
            Duration exportTimeout,
            Map<String, String> headers) {
        var builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(requireNonNull(endpoint, "endpoint must not be null"))
                .setConnectTimeout(positive(connectTimeout, "connectTimeout"))
                .setTimeout(positive(exportTimeout, "exportTimeout"));
        requireNonNull(headers, "headers must not be null").forEach(builder::addHeader);
        return builder.build();
    }

    private static Resource resource(Map<String, String> resourceAttributes) {
        var attributes = Attributes.builder();
        requireNonNull(resourceAttributes, "resourceAttributes must not be null")
                .forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        attributes.put(key, value);
                    }
                });
        return Resource.getDefault().merge(Resource.create(attributes.build()));
    }

    private static Duration positive(Duration value, String name) {
        requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private void throwIfFailed() {
        if (failureAwareSpanProcessor != null) {
            failureAwareSpanProcessor.throwIfFailed();
        }
    }

    private void clearFailure() {
        if (failureAwareSpanProcessor != null) {
            failureAwareSpanProcessor.clearFailure();
        }
    }

    @Override
    public void close() {
        tracerProvider.close();
    }

    private record ForcedIds(String traceId, String spanId, String traceFlags) {
        private static ForcedIds from(ScoreActivityContext context) {
            if (context == null) {
                return new ForcedIds(null, null, null);
            }
            return new ForcedIds(
                    TraceId.isValid(context.traceId()) ? context.traceId() : null,
                    SpanId.isValid(context.spanId()) ? context.spanId() : null,
                    context.traceFlags());
        }
    }

    private static final class ForcedEventIdGenerator implements IdGenerator {
        private final ThreadLocal<ForcedIds> current = new ThreadLocal<>();

        private void use(ForcedIds ids) {
            current.set(ids);
        }

        private void clear() {
            current.remove();
        }

        private String currentTraceFlags() {
            ForcedIds ids = current.get();
            return ids == null ? null : ids.traceFlags();
        }

        @Override
        public String generateSpanId() {
            ForcedIds ids = current.get();
            return ids != null && ids.spanId() != null ? ids.spanId() : RANDOM_IDS.generateSpanId();
        }

        @Override
        public String generateTraceId() {
            ForcedIds ids = current.get();
            return ids != null && ids.traceId() != null ? ids.traceId() : RANDOM_IDS.generateTraceId();
        }

        @Override
        public boolean generatesRandomTraceIds() {
            return false;
        }
    }

    private static final class EventTraceFlagsSampler implements Sampler {
        private final ForcedEventIdGenerator ids;

        private EventTraceFlagsSampler(ForcedEventIdGenerator ids) {
            this.ids = ids;
        }

        @Override
        public SamplingResult shouldSample(
                Context parentContext,
                String traceId,
                String name,
                SpanKind spanKind,
                Attributes attributes,
                List<LinkData> parentLinks) {
            String flags = ids.currentTraceFlags();
            if (flags != null) {
                try {
                    if (!TraceFlags.fromHex(flags, 0).isSampled()) {
                        return SamplingResult.recordOnly();
                    }
                } catch (RuntimeException ignored) {
                    // Invalid optional flags fall back to sampled activity delivery.
                }
            }
            return SamplingResult.recordAndSample();
        }

        @Override
        public String getDescription() {
            return "ScoreActivityEventTraceFlagsSampler";
        }
    }

    private static final class SynchronousExportSpanProcessor implements SpanProcessor {
        private final SpanExporter exporter;
        private final long timeoutNanos;
        private final ThreadLocal<RuntimeException> failure = new ThreadLocal<>();

        private SynchronousExportSpanProcessor(SpanExporter exporter, Duration timeout) {
            this.exporter = requireNonNull(exporter, "exporter must not be null");
            this.timeoutNanos = positive(timeout, "exportTimeout").toNanos();
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            CompletableResultCode result = exporter.export(List.of(span.toSpanData()))
                    .join(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!result.isSuccess()) {
                Throwable cause = result.getFailureThrowable();
                failure.set(new IllegalStateException("OTLP activity export failed", cause));
            }
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        private void throwIfFailed() {
            RuntimeException exception = failure.get();
            if (exception != null) {
                throw exception;
            }
        }

        private void clearFailure() {
            failure.remove();
        }

        @Override
        public CompletableResultCode shutdown() {
            return exporter.shutdown();
        }

        @Override
        public CompletableResultCode forceFlush() {
            return exporter.flush();
        }
    }
}
