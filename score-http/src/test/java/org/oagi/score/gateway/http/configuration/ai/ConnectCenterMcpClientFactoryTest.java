package org.oagi.score.gateway.http.configuration.ai;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.application_management.service.BrokerJwtService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectCenterMcpClientFactoryTest {

    @Test
    void propagatesTheCurrentPrivateSpanToMcpHttpRequests() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var span = sdk.getTracer("test").spanBuilder("tool").startSpan();
        try (var ignored = span.makeCurrent()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://mcp.example/mcp"));

            ConnectCenterMcpClientFactory.injectCurrentTrace(request);
            String body = ConnectCenterMcpClientFactory.injectTraceIntoMcpBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"search\"}}");

            assertThat(request.build().headers().firstValue("traceparent"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains(span.getSpanContext().getTraceId())
                            .contains(span.getSpanContext().getSpanId()));
            assertThat(body).contains("\"_meta\"")
                    .contains("\"traceparent\":\"00-" + span.getSpanContext().getTraceId());
        } finally {
            span.end();
        }
        assertThat(spans.getFinishedSpanItems()).singleElement().satisfies(exported ->
                assertThat(exported.getAttributes().get(
                        AttributeKey.stringKey("jsonrpc.request.id"))).isEqualTo("42"));
        sdk.close();
    }

    @Test
    void requesterTokenOutlivesTheLongestSdkRequest() {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getMcp().getAuth().setIssuerUrl("https://issuer.example");
        properties.getMcp().getAuth().setTokenTtlSeconds(300);
        properties.setRequestTimeout(Duration.ofMinutes(10));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.client.streamable-http.connections.connect-center-mcp.url",
                        "https://mcp.example");
        BrokerJwtService broker = mock(BrokerJwtService.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(broker.issueToken(requester, "https://issuer.example", "connect-center-mcp", "ES256", 660))
                .thenReturn("token");

        ConnectCenterMcpClientFactory.McpConnection connection =
                new ConnectCenterMcpClientFactory(properties, environment, broker).connection(requester);

        assertThat(connection).isNotNull();
        assertThat(connection.bearerToken()).isEqualTo("token");
        verify(broker).issueToken(requester, "https://issuer.example", "connect-center-mcp", "ES256", 660);
    }
}
