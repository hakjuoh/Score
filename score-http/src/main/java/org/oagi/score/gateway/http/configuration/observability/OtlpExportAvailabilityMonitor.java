package org.oagi.score.gateway.http.configuration.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports, as a single readable warning, that an enabled OTLP trace or metric export has nowhere to
 * go. The exporter's own per-attempt error with its stack trace is silenced in application.yml
 * (logging.level for HttpExporter) because it does not name the cause; this check names the endpoint
 * and the switch that turns the export off. It logs at startup and then every probe interval while
 * the endpoint stays unreachable, and once more when it recovers.
 */
@Configuration
@EnableScheduling
public class OtlpExportAvailabilityMonitor {

    private static final Logger logger = LoggerFactory.getLogger(OtlpExportAvailabilityMonitor.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;

    private final Environment environment;
    private boolean wasUnreachable;

    public OtlpExportAvailabilityMonitor(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        check();
    }

    @Scheduled(initialDelayString = "${score.observability.otlp-probe-interval:5m}",
            fixedDelayString = "${score.observability.otlp-probe-interval:5m}")
    public void checkPeriodically() {
        check();
    }

    private synchronized void check() {
        List<String> unreachable = new ArrayList<>();
        probe("traces", "management.tracing.export.otlp.enabled",
                "management.opentelemetry.tracing.export.otlp.endpoint", "OTEL_TRACES_EXPORT_ENABLED", unreachable);
        probe("metrics", "management.otlp.metrics.export.enabled",
                "management.otlp.metrics.export.url", "OTEL_METRICS_EXPORT_ENABLED", unreachable);
        if (!unreachable.isEmpty()) {
            wasUnreachable = true;
            logger.warn("OTLP export is not working: {}. Telemetry is being dropped; the application itself is "
                    + "unaffected. Start an OTLP receiver at that address, or turn the export off with the "
                    + "switch named above. This check repeats every {} while the problem lasts.",
                    String.join("; ", unreachable),
                    environment.getProperty("score.observability.otlp-probe-interval", "5m"));
        } else if (wasUnreachable) {
            wasUnreachable = false;
            logger.info("OTLP export endpoint is reachable again.");
        }
    }

    private void probe(String signal, String enabledProperty, String endpointProperty, String switchName,
                       List<String> unreachable) {
        if (!environment.getProperty(enabledProperty, Boolean.class, false)) {
            return;
        }
        String endpoint = environment.getProperty(endpointProperty);
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(endpoint);
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MILLIS);
            }
        } catch (IOException | IllegalArgumentException e) {
            unreachable.add(signal + " endpoint " + endpoint + " cannot be reached ("
                    + e.getClass().getSimpleName() + "; switch " + switchName + "=false)");
        }
    }
}
