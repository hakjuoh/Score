package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI SDK view of Spring Boot's standard {@code management.*} OpenTelemetry settings.
 * Keeping the standard property names lets a future application-wide OTel integration
 * reuse the same endpoints, resource attributes, sampling, and export cadence.
 */
@ConfigurationProperties("management")
public class ScoreAiObservabilityManagementProperties {

    private final Tracing tracing = new Tracing();
    private final OpenTelemetry opentelemetry = new OpenTelemetry();
    private final Otlp otlp = new Otlp();

    public Tracing getTracing() { return tracing; }
    public OpenTelemetry getOpentelemetry() { return opentelemetry; }
    public Otlp getOtlp() { return otlp; }

    public static class Tracing {
        private final Sampling sampling = new Sampling();
        private final TracingExport export = new TracingExport();
        public Sampling getSampling() { return sampling; }
        public TracingExport getExport() { return export; }
    }

    public static class Sampling {
        private double probability = 1.0;
        public double getProbability() { return probability; }
        public void setProbability(double probability) { this.probability = probability; }
    }

    public static class TracingExport {
        private boolean enabled = true;
        private final OtlpTracingExport otlp = new OtlpTracingExport();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public OtlpTracingExport getOtlp() { return otlp; }
    }

    public static class OtlpTracingExport {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class OpenTelemetry {
        private boolean enabled = true;
        private Map<String, String> resourceAttributes = defaultResourceAttributes();
        private final OpenTelemetryTracing tracing = new OpenTelemetryTracing();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Map<String, String> getResourceAttributes() { return resourceAttributes; }
        public void setResourceAttributes(Map<String, String> resourceAttributes) {
            this.resourceAttributes = resourceAttributes != null
                    ? new LinkedHashMap<>(resourceAttributes) : new LinkedHashMap<>();
        }
        public OpenTelemetryTracing getTracing() { return tracing; }

        private static Map<String, String> defaultResourceAttributes() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("service.namespace", "oagi.score");
            attributes.put("deployment.environment.name", "unknown");
            return attributes;
        }
    }

    public static class OpenTelemetryTracing {
        private final TraceExport export = new TraceExport();
        private Sampler sampler = Sampler.PARENT_BASED_TRACE_ID_RATIO;

        public TraceExport getExport() { return export; }
        public Sampler getSampler() { return sampler; }
        public void setSampler(Sampler sampler) {
            this.sampler = sampler != null ? sampler : Sampler.PARENT_BASED_TRACE_ID_RATIO;
        }
    }

    public enum Sampler {
        ALWAYS_ON, ALWAYS_OFF, TRACE_ID_RATIO,
        PARENT_BASED_ALWAYS_ON, PARENT_BASED_ALWAYS_OFF, PARENT_BASED_TRACE_ID_RATIO
    }

    public static class TraceExport {
        private Duration timeout = Duration.ofSeconds(10);
        private Duration scheduleDelay = Duration.ofSeconds(5);
        private final OtlpTraceExport otlp = new OtlpTraceExport();

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Duration getScheduleDelay() { return scheduleDelay; }
        public void setScheduleDelay(Duration scheduleDelay) { this.scheduleDelay = scheduleDelay; }
        public OtlpTraceExport getOtlp() { return otlp; }
    }

    public static class OtlpTraceExport {
        private String endpoint = "http://127.0.0.1:4318/v1/traces";
        private Duration timeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
        }
    }

    public static class Otlp {
        private final Metrics metrics = new Metrics();
        public Metrics getMetrics() { return metrics; }
    }

    public static class Metrics {
        private final MetricExport export = new MetricExport();
        public MetricExport getExport() { return export; }
    }

    public static class MetricExport {
        private boolean enabled = true;
        private String url = "http://127.0.0.1:4318/v1/metrics";
        private Duration step = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(10);
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Duration getStep() { return step; }
        public void setStep(Duration step) { this.step = step; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
        }
    }
}
