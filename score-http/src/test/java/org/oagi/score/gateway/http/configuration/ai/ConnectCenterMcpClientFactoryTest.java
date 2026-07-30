package org.oagi.score.gateway.http.configuration.ai;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.application_management.service.BrokerJwtService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ConnectCenterMcpClientFactoryTest {

    @Test
    void discoversEveryMcpToolPageOnceAndRetainsProtocolMetadata() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool first = new McpSchema.Tool("first_tool", "First", "First page.",
                Map.of("type", "object"), Map.of("type", "object"),
                McpSchema.ToolAnnotations.builder().readOnlyHint(true).build(),
                Map.of("page", 1), List.of());
        McpSchema.Tool second = new McpSchema.Tool("second_tool", "Second", "Second page.",
                Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer"))),
                Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))),
                McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(false).destructiveHint(true).idempotentHint(false).build(),
                Map.of("page", 2), List.of(new McpSchema.Icon(
                        "https://example.test/tool.svg", "image/svg+xml", List.of("any"), "dark")));
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(first), "page-2", Map.of("page", 1)));
        when(client.listTools("page-2")).thenReturn(
                new McpSchema.ListToolsResult(List.of(second), null, Map.of("page", 2)));

        ConnectCenterMcpClientFactory.DiscoveredTools discovered =
                ConnectCenterMcpClientFactory.discoverTools(client);

        assertThat(discovered.callbacks().getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("first_tool", "second_tool");
        assertThat(discovered.readOnlyNames()).containsExactly("first_tool");
        assertThat(discovered.catalog()).containsExactly(first, second);
        assertThat(discovered.catalog().get(1).outputSchema()).containsKey("properties");
        assertThat(discovered.catalog().get(1).annotations().destructiveHint()).isTrue();
        assertThat(discovered.catalog().get(1).meta()).containsEntry("page", 2);
        assertThat(discovered.catalog().get(1).icons()).hasSize(1);
        verify(client).listTools();
        verify(client).listTools("page-2");
    }

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
        ScoreMcpClientProperties mcpProperties = mcpProperties("https://mcp.example");
        mcpProperties.connection("connect-center-mcp").getAuth()
                .setIssuerUrl("https://issuer.example");
        mcpProperties.connection("connect-center-mcp").getAuth().setTokenTtlSeconds(300);
        properties.setElicitationTimeout(Duration.ofMinutes(10));
        BrokerJwtService broker = mock(BrokerJwtService.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(broker.issueToken(requester, "https://issuer.example", "connect-center-mcp", "ES256", 660))
                .thenReturn("token");

        ConnectCenterMcpClientFactory.McpConnection connection =
                new ConnectCenterMcpClientFactory(properties, mcpProperties, broker)
                        .connection(requester);

        assertThat(connection).isNotNull();
        assertThat(connection.bearerToken()).isEqualTo("token");
        verify(broker).issueToken(requester, "https://issuer.example", "connect-center-mcp", "ES256", 660);
    }

    @Test
    void refreshesRequesterTokenForEveryMcpHttpRequest() {
        ScoreAiProperties properties = new ScoreAiProperties();
        ScoreMcpClientProperties mcpProperties = mcpProperties(null);
        mcpProperties.connection("connect-center-mcp").getAuth()
                .setIssuerUrl("https://issuer.example");
        properties.setElicitationTimeout(Duration.ofMinutes(10));
        BrokerJwtService broker = mock(BrokerJwtService.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(broker.issueToken(requester, "https://issuer.example", "connect-center-mcp", "ES256", 660))
                .thenReturn("token-one", "token-two");
        ConnectCenterMcpClientFactory factory = new ConnectCenterMcpClientFactory(
                properties, mcpProperties, broker);
        HttpRequest.Builder first = HttpRequest.newBuilder(URI.create("https://mcp.example/mcp"));
        HttpRequest.Builder second = HttpRequest.newBuilder(URI.create("https://mcp.example/mcp"));

        factory.authorizeRequest(requester, first);
        factory.authorizeRequest(requester, second);

        assertThat(first.build().headers().firstValue("Authorization"))
                .contains("Bearer token-one");
        assertThat(second.build().headers().firstValue("Authorization"))
                .contains("Bearer token-two");
        verify(broker, times(2)).issueToken(requester, "https://issuer.example",
                "connect-center-mcp", "ES256", 660);
    }

    private ScoreMcpClientProperties mcpProperties(String url) {
        ScoreMcpClientProperties properties = new ScoreMcpClientProperties();
        ScoreMcpClientProperties.Connection connection = new ScoreMcpClientProperties.Connection();
        connection.setUrl(url);
        properties.getStreamableHttp().getConnections().put("connect-center-mcp", connection);
        return properties;
    }
}
