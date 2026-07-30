package org.oagi.score.gateway.http.api.ai_management.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiObservabilityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Delivers canonical, content-minimized execution events to a configured outbound WebHook. */
@Component
@Order(200)
final class ExecutionWebhookListener implements ExecutionEventListener, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionWebhookListener.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final ScoreAiObservabilityProperties.Webhook properties;
    private final HttpClient client;
    private final ThreadPoolExecutor deliveries;

    @Autowired
    ExecutionWebhookListener(ObjectMapper objectMapper, ScoreAiObservabilityProperties properties) {
        this(objectMapper, properties.getWebhook(), HttpClient.newBuilder()
                .connectTimeout(bounded(properties.getWebhook().getConnectTimeout(),
                        Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ExecutionWebhookListener(ObjectMapper objectMapper,
                             ScoreAiObservabilityProperties.Webhook properties,
                             HttpClient client) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.deliveries = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, Math.min(100_000,
                        properties.getQueueCapacity()))), runnable -> {
                    Thread thread = Thread.ofPlatform().name("score-ai-webhook").daemon(true)
                            .unstarted(runnable);
                    return thread;
                }, (task, executor) -> enqueueWithBackpressure(task, executor));
    }

    @PostConstruct
    void validateConfiguration() {
        if (!properties.isEnabled()) return;
        if (!StringUtils.hasText(properties.getSecret())
                || properties.getSecret().length() < 32) {
            throw new IllegalStateException(
                    "Enabled AI execution WebHook requires a secret of at least 32 characters");
        }
        if (!StringUtils.hasText(properties.getKeyId())
                || !properties.getKeyId().matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalStateException(
                    "Enabled AI execution WebHook requires a bounded signature key ID");
        }
        if (endpoint() == null) {
            throw new IllegalStateException(
                    "Enabled AI execution WebHook requires an allowed endpoint");
        }
    }

    @Override
    public void onEvent(ExecutionObservation event) {
        if (!properties.isEnabled()) return;
        URI endpoint = endpoint();
        String secret = properties.getSecret();
        if (endpoint == null || !StringUtils.hasText(secret) || secret.length() < 32) {
            LOGGER.error("AI execution WebHook is enabled but endpoint or secret is missing");
            return;
        }
        byte[] body = body(event);
        String eventId = event.attributes().get(ExecutionEventPublisher.EVENT_ID).toString();
        String sequence = event.attributes().get(ExecutionEventPublisher.EVENT_SEQUENCE).toString();
        String timestamp = event.occurredAt().toString();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(bounded(properties.getTimeout(), Duration.ofSeconds(5),
                        Duration.ofSeconds(30)))
                .header("Content-Type", "application/json")
                .header("User-Agent", "score-ai-observability-webhook/1")
                .header("X-Score-Event-Id", eventId)
                .header("X-Score-Event-Sequence", sequence)
                .header("X-Score-Event-Timestamp", timestamp)
                .header("X-Score-Signature-Key-Id", safeKeyId(properties.getKeyId()))
                .header("X-Score-Signature", "sha256=" + signature(secret, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            deliveries.execute(() -> deliver(eventId, request));
        } catch (RejectedExecutionException shuttingDown) {
            LOGGER.warn("AI execution WebHook rejected event {} during shutdown", eventId);
        }
    }

    @Override
    public boolean causal() {
        return false;
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    private void deliver(String eventId, HttpRequest request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<Void> response = client.send(request,
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return;
                lastFailure = new IllegalStateException(
                        "WebHook returned HTTP " + response.statusCode());
                if (response.statusCode() >= 400 && response.statusCode() < 500
                        && response.statusCode() != 408 && response.statusCode() != 429) break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.warn("AI execution WebHook delivery was interrupted for event {}",
                        eventId, interrupted);
                return;
            } catch (java.io.IOException | RuntimeException failure) {
                lastFailure = failure instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(failure);
            }
            if (attempt < 3 && !retryDelay(attempt, eventId)) return;
        }
        if (lastFailure != null) {
            LOGGER.warn("AI execution WebHook delivery failed after retries for event {}",
                    eventId, lastFailure);
        }
    }

    private boolean retryDelay(int attempt, String eventId) {
        try {
            Thread.sleep(100L << (attempt - 1));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("AI execution WebHook retry was interrupted for event {}",
                    eventId, interrupted);
            return false;
        }
    }

    private byte[] body(ExecutionObservation event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("specVersion", "1.0");
        payload.put("eventId", event.attributes().get(ExecutionEventPublisher.EVENT_ID));
        payload.put("sequence", event.attributes().get(ExecutionEventPublisher.EVENT_SEQUENCE));
        payload.put("occurredAt", event.occurredAt());
        payload.put("type", event.type());
        payload.put("requestId", event.scope().requestId());
        payload.put("conversationId", event.scope().conversationId());
        payload.put("generation", event.scope().generation());
        payload.put("purpose", event.scope().purpose().name().toLowerCase());
        payload.put("attributes", publicAttributes(event));
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize AI execution WebHook event", failure);
        }
    }

    private Map<String, Object> publicAttributes(ExecutionObservation event) {
        Set<String> allowed = Set.of(
                ExecutionEventPublisher.EVENT_ID,
                ExecutionEventPublisher.EVENT_SEQUENCE,
                ExecutionEventPublisher.EVENT_OCCURRED_AT,
                "agent_run_id", "agent_id", "model_id",
                "provider", "phase", "parent_operation_id", "permission_mode",
                "message_kind", "source", "tool_name",
                "workflow_node_id", "workflow_parent_node_id", "workflow_fanout_id",
                "workflow", "tool_id", "effect", "failure_type", "action",
                "decision_id", "policy_id", "outcome");
        Map<String, Object> safe = new LinkedHashMap<>();
        allowed.forEach(name -> {
            Object value = event.attributes().get(name);
            if (value != null) safe.put(name, value);
        });
        AiExecutionLifecycle.from(event).ifPresent(lifecycle ->
                safe.put("lifecycle", publicLifecycle(lifecycle)));
        return Map.copyOf(safe);
    }

    private Map<String, Object> publicLifecycle(AiExecutionLifecycle lifecycle) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", lifecycle.eventType());
        result.put("subtype", lifecycle.subtype());
        if (lifecycle.toolCallId() != null) result.put("toolCallId", lifecycle.toolCallId());
        if (lifecycle.toolName() != null) result.put("toolName", lifecycle.toolName());
        if (lifecycle.toolCallSequence() != null) {
            result.put("toolCallSequence", lifecycle.toolCallSequence());
        }
        Set<String> metadata = Set.of(
                ExecutionEventPublisher.EVENT_ID, ExecutionEventPublisher.EVENT_SEQUENCE,
                ExecutionEventPublisher.EVENT_OCCURRED_AT, "attempt", "max_attempts",
                "status_code", "duration_ms", "result_truncated", "failure_type",
                "mcp", "workflow", "node_id", "parent_node_id", "fanout_id",
                "depth", "member_count", "agent_run_id", "completed", "failed");
        Map<String, Object> publicMetadata = new LinkedHashMap<>();
        metadata.forEach(name -> {
            Object value = lifecycle.metadata().get(name);
            if (value != null) publicMetadata.put(name, value);
        });
        if (!publicMetadata.isEmpty()) result.put("metadata", Map.copyOf(publicMetadata));
        return Map.copyOf(result);
    }

    private URI endpoint() {
        if (!StringUtils.hasText(properties.getEndpoint())) return null;
        try {
            URI endpoint = URI.create(properties.getEndpoint().strip());
            if (!endpoint.isAbsolute() || !StringUtils.hasText(endpoint.getHost())
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null) return null;
            if ("https".equalsIgnoreCase(endpoint.getScheme())) {
                return properties.isAllowPrivateNetwork() || publicAddress(endpoint.getHost())
                        ? endpoint : null;
            }
            return properties.isAllowInsecureHttp()
                    && "http".equalsIgnoreCase(endpoint.getScheme())
                    && isLoopback(endpoint.getHost()) ? endpoint : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }

    private boolean publicAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || uniqueLocalIpv6(address)) return false;
            }
            return true;
        } catch (java.net.UnknownHostException ignored) {
            return false;
        }
    }

    private boolean uniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static void enqueueWithBackpressure(Runnable task, ThreadPoolExecutor executor) {
        try {
            while (!executor.isShutdown()) {
                if (executor.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) return;
            }
            throw new RejectedExecutionException("WebHook dispatcher is shutting down");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while queueing WebHook", interrupted);
        }
    }

    private static String signature(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Could not sign AI execution WebHook event", failure);
        }
    }

    private static String safeKeyId(String keyId) {
        return keyId != null && keyId.matches("[A-Za-z0-9_.-]{1,64}") ? keyId : "default";
    }

    private static Duration bounded(Duration value, Duration fallback, Duration maximum) {
        Duration positive = value != null && !value.isNegative() && !value.isZero()
                ? value : fallback;
        return positive.compareTo(maximum) <= 0 ? positive : maximum;
    }

    @Override
    @PreDestroy
    public void close() {
        deliveries.shutdown();
        try {
            if (!deliveries.awaitTermination(30, TimeUnit.SECONDS)) {
                int dropped = deliveries.shutdownNow().size();
                LOGGER.warn("AI execution WebHook shutdown discarded {} queued events", dropped);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            int dropped = deliveries.shutdownNow().size();
            LOGGER.warn("AI execution WebHook shutdown was interrupted; {} events were discarded",
                    dropped);
        }
    }
}
