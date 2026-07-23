package org.oagi.score.gateway.http.configuration.ai;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds an isolated SDK without registering it globally. Consequently Spring MVC,
 * scheduled jobs, JDBC, and JVM metrics cannot enter the AI observability signals.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ScoreAiObservabilityProperties.class,
        ScoreAiObservabilityManagementProperties.class})
public class ScoreAiObservabilityConfiguration {

    @Bean(destroyMethod = "close")
    ScoreAiObservabilitySdk scoreAiObservabilitySdk(
            ScoreAiObservabilityProperties properties,
            ScoreAiObservabilityManagementProperties management,
            ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "development";
        var openTelemetry = management.getOpentelemetry();
        if (!properties.isEnabled() || !openTelemetry.isEnabled()) {
            return new ScoreAiObservabilitySdk(
                    OpenTelemetry.noop(), properties.getServiceName(), version, null);
        }
        var resourceAttributes = Attributes.builder();
        openTelemetry.getResourceAttributes().forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                resourceAttributes.put(AttributeKey.stringKey(name), value);
            }
        });
        // The AI SDK has a distinct service identity even when the shared management
        // resource attributes later serve an application-wide SDK as well.
        resourceAttributes.put(AttributeKey.stringKey("service.name"), properties.getServiceName());
        resourceAttributes.put(AttributeKey.stringKey("service.version"), version);
        Resource resource = Resource.getDefault().merge(Resource.create(resourceAttributes.build()));

        var traceExport = openTelemetry.getTracing().getExport();
        var traceOtlp = traceExport.getOtlp();

        var tracerProviderBuilder = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler(openTelemetry.getTracing().getSampler(),
                        management.getTracing().getSampling().getProbability()));
        if (management.getTracing().getExport().isEnabled()
                && management.getTracing().getExport().getOtlp().isEnabled()) {
            var spanExporterBuilder = OtlpHttpSpanExporter.builder()
                    .setEndpoint(traceOtlp.getEndpoint())
                    .setTimeout(traceOtlp.getTimeout())
                    .setConnectTimeout(traceOtlp.getConnectTimeout());
            traceOtlp.getHeaders().forEach(spanExporterBuilder::addHeader);
            OtlpHttpSpanExporter spanExporter = spanExporterBuilder.build();
            tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                    .setScheduleDelay(traceExport.getScheduleDelay())
                    .setExporterTimeout(traceExport.getTimeout())
                    .build());
        }
        SdkTracerProvider tracerProvider = tracerProviderBuilder.build();

        var metricExport = management.getOtlp().getMetrics().getExport();
        SdkMeterProviderBuilder meterProviderBuilder = SdkMeterProvider.builder()
                .setResource(resource);
        if (metricExport.isEnabled()) {
            var metricExporterBuilder = OtlpHttpMetricExporter.builder()
                    .setEndpoint(metricExport.getUrl())
                    .setTimeout(metricExport.getReadTimeout())
                    .setConnectTimeout(metricExport.getConnectTimeout());
            metricExport.getHeaders().forEach(metricExporterBuilder::addHeader);
            OtlpHttpMetricExporter metricExporter = metricExporterBuilder.build();
            meterProviderBuilder.registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                    .setInterval(metricExport.getStep())
                    .build());
        }
        SdkMeterProvider meterProvider = meterProviderBuilder.build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();
        return new ScoreAiObservabilitySdk(sdk, properties.getServiceName(), version, sdk);
    }

    private static double boundedProbability(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    private static Sampler sampler(ScoreAiObservabilityManagementProperties.Sampler sampler,
                                   double probability) {
        Sampler ratio = Sampler.traceIdRatioBased(boundedProbability(probability));
        return switch (sampler) {
            case ALWAYS_ON -> Sampler.alwaysOn();
            case ALWAYS_OFF -> Sampler.alwaysOff();
            case TRACE_ID_RATIO -> ratio;
            case PARENT_BASED_ALWAYS_ON -> Sampler.parentBased(Sampler.alwaysOn());
            case PARENT_BASED_ALWAYS_OFF -> Sampler.parentBased(Sampler.alwaysOff());
            case PARENT_BASED_TRACE_ID_RATIO -> Sampler.parentBased(ratio);
        };
    }

    public static final class ScoreAiObservabilitySdk implements AutoCloseable {
        private final OpenTelemetry openTelemetry;
        private final String serviceName;
        private final String serviceVersion;
        private final AutoCloseable closeable;

        private ScoreAiObservabilitySdk(OpenTelemetry openTelemetry, String serviceName,
                                        String serviceVersion, AutoCloseable closeable) {
            this.openTelemetry = openTelemetry;
            this.serviceName = serviceName;
            this.serviceVersion = serviceVersion;
            this.closeable = closeable;
        }

        public OpenTelemetry openTelemetry() { return openTelemetry; }
        public String serviceName() { return serviceName; }
        public String serviceVersion() { return serviceVersion; }

        @Override
        public void close() throws Exception {
            if (closeable != null) closeable.close();
        }
    }
}
