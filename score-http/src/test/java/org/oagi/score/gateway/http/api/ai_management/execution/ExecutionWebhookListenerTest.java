package org.oagi.score.gateway.http.api.ai_management.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiObservabilityProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionWebhookListenerTest {

    @Test
    void deliversTheCanonicalEventWithMatchingHeadersAndHmac() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            headers.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            delivered.countDown();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            ScoreAiObservabilityProperties properties = new ScoreAiObservabilityProperties();
            properties.getWebhook().setEnabled(true);
            properties.getWebhook().setEndpoint("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/events");
            properties.getWebhook().setSecret("test-secret-that-is-at-least-32-bytes");
            properties.getWebhook().setAllowInsecureHttp(true);
            try (ExecutionWebhookListener webhook = new ExecutionWebhookListener(mapper, properties)) {
                ExecutionEventPublisher publisher = new ExecutionEventPublisher(List.of(webhook));
                publisher.observe(ExecutionObservation.of(
                        "workflow.root.started", scope(), Map.of()));

                publisher.observe(AiExecutionLifecycle.from(
                        org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent.detail(
                                "workflow_started", "private prompt", Map.of(
                                        "workflow", "research", "node_id", "workflow-7",
                                        "server_address", "internal.example.test")))
                        .observation(scope()));
                assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();

                JsonNode payload = mapper.readTree(body.get());
                assertThat(payload.path("eventId").asText()).isNotBlank();
                assertThat(payload.path("sequence").asLong()).isEqualTo(2L);
                assertThat(payload.path("attributes").toString()).doesNotContain("private prompt");
                assertThat(payload.path("attributes").toString())
                        .doesNotContain("internal.example.test", "server_address");
                assertThat(headers.get().getFirst("X-Score-Event-Id"))
                        .isEqualTo(payload.path("eventId").asText());
                assertThat(headers.get().getFirst("X-Score-Event-Sequence")).isEqualTo("2");
                assertThat(headers.get().getFirst("X-Score-Signature-Key-Id"))
                        .isEqualTo("default");
                assertThat(headers.get().getFirst("X-Score-Signature"))
                        .isEqualTo("sha256=" + hmac(
                                "test-secret-that-is-at-least-32-bytes", body.get()));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPrivateHttpsTargetsUnlessTheOperatorExplicitlyOptsIn() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ScoreAiObservabilityProperties.Webhook properties =
                new ScoreAiObservabilityProperties.Webhook();
        properties.setEnabled(true);
        properties.setEndpoint("https://127.0.0.1/events");
        properties.setSecret("test-secret-that-is-at-least-32-bytes");
        HttpClient client = mock(HttpClient.class);

        try (ExecutionWebhookListener webhook = new ExecutionWebhookListener(
                mapper, properties, client)) {
            assertThatThrownBy(webhook::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class);
            Instant now = Instant.now();
            webhook.onEvent(new ExecutionObservation("test", scope(), now, Map.of(
                    ExecutionEventPublisher.EVENT_ID, "event-1",
                    ExecutionEventPublisher.EVENT_SEQUENCE, 1L,
                    ExecutionEventPublisher.EVENT_OCCURRED_AT, now.toString())));
        }

        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesTransientResponsesInFifoWorker() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ScoreAiObservabilityProperties.Webhook properties =
                new ScoreAiObservabilityProperties.Webhook();
        properties.setEnabled(true);
        properties.setEndpoint("http://127.0.0.1/events");
        properties.setAllowInsecureHttp(true);
        properties.setSecret("test-secret-that-is-at-least-32-bytes");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> unavailable = mock(HttpResponse.class);
        HttpResponse<Void> accepted = mock(HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(503);
        when(accepted.statusCode()).thenReturn(204);
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(unavailable, unavailable, accepted);
        Instant now = Instant.now();

        try (ExecutionWebhookListener webhook = new ExecutionWebhookListener(
                mapper, properties, client)) {
            webhook.validateConfiguration();
            webhook.onEvent(new ExecutionObservation("test", scope(), now, Map.of(
                    ExecutionEventPublisher.EVENT_ID, "event-1",
                    ExecutionEventPublisher.EVENT_SEQUENCE, 1L,
                    ExecutionEventPublisher.EVENT_OCCURRED_AT, now.toString())));
        }

        verify(client, times(3)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    private ExecutionScope scope() {
        return new ExecutionScope("request-1", "conversation-1", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
    }

    private String hmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
