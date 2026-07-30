package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.application_management.service.BrokerJwtService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Creates requester-scoped sessions for configured MCP servers. */
@Component
public class ConnectCenterMcpClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectCenterMcpClientFactory.class);
    private static final ToolCallbackProvider NO_TOOLS = () -> new ToolCallback[0];
    private static final TextMapSetter<HttpRequest.Builder> TRACE_HEADER_SETTER =
            (builder, key, value) -> builder.setHeader(key, value);
    private static final TextMapSetter<Map<String, String>> TRACE_META_SETTER = Map::put;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScoreAiProperties properties;
    private final ScoreMcpClientProperties mcpProperties;
    private final BrokerJwtService brokerJwtService;

    public ConnectCenterMcpClientFactory(ScoreAiProperties properties,
                                         ScoreMcpClientProperties mcpProperties,
                                         BrokerJwtService brokerJwtService) {
        this.properties = properties;
        this.mcpProperties = mcpProperties;
        this.brokerJwtService = brokerJwtService;
    }

    public McpSession open(ScoreUser requester) {
        return open(connectionName(), requester, null, () -> { },
                longer(mcpProperties.getRequestTimeout(), properties.getElicitationTimeout()),
                mcpProperties.getInitializationTimeout());
    }

    /** Opens a short-lived session whose initialization and tool discovery are tightly bounded. */
    public McpSession openForStatus(String connectionName, ScoreUser requester) {
        Duration statusTimeout = mcpProperties.getStatusTimeout();
        return open(connectionName, requester, null, () -> { }, statusTimeout, statusTimeout);
    }

    public McpSession open(
            ScoreUser requester,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler) {
        return open(connectionName(), requester, elicitationHandler, () -> { },
                longer(mcpProperties.getRequestTimeout(), properties.getElicitationTimeout()),
                mcpProperties.getInitializationTimeout());
    }

    public McpSession open(
            ScoreUser requester,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler,
            Runnable progress) {
        return open(connectionName(), requester, elicitationHandler, progress,
                longer(mcpProperties.getRequestTimeout(), properties.getElicitationTimeout()),
                mcpProperties.getInitializationTimeout());
    }

    private McpSession open(
            String connectionName,
            ScoreUser requester,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler,
            Runnable progress,
            Duration requestTimeout,
            Duration initializationTimeout) {
        Objects.requireNonNull(progress, "progress");
        McpConnection connection = connection(connectionName, requester);
        if (connection == null) {
            return new McpSession(null, NO_TOOLS, Set.of(), McpTelemetry.EMPTY);
        }
        String baseUrl = connection.baseUrl();
        String endpoint = connection.endpoint();

        HttpClientStreamableHttpTransport.Builder transport = HttpClientStreamableHttpTransport
                .builder(baseUrl)
                .endpoint(endpoint)
                .httpRequestCustomizer((builder, method, uri, body, transportContext) -> {
                    authorizeRequest(connectionName, requester, builder);
                    injectCurrentTrace(builder);
                    String enrichedBody = injectTraceIntoMcpBody(body);
                    if (enrichedBody != null && !enrichedBody.equals(body)) {
                        builder.method(method, BodyPublishers.ofString(enrichedBody));
                    }
                })
                .openConnectionOnStartup(false);

        var clientBuilder = McpClient.sync(transport.build())
                .requestTimeout(requestTimeout)
                .initializationTimeout(initializationTimeout)
                .progressConsumer(notification -> progress.run());
        if (elicitationHandler != null) {
            clientBuilder.elicitation(elicitationHandler).applyElicitationDefaults(true);
        }
        McpSyncClient client = clientBuilder.build();
        try {
            McpSchema.InitializeResult initialized = client.initialize();
            DiscoveredTools discovered = discoverTools(client);
            return new McpSession(client, discovered.callbacks(), discovered.readOnlyNames(),
                    discovered.catalog(),
                    McpTelemetry.from(connectionName, connection, initialized));
        } catch (RuntimeException exception) {
            client.closeGracefully();
            throw exception;
        }
    }

    /**
     * Resolves the tools the connect-center-mcp server itself declares as read-only
     * through the MCP readOnlyHint tool annotation. Fail-closed: a tool without an
     * explicit readOnlyHint=true annotation is treated as data-changing.
     */
    static DiscoveredTools discoverTools(McpSyncClient client) {
        List<ToolCallback> callbacks = new ArrayList<>();
        List<McpSchema.Tool> catalog = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Set<String> callbackNames = new HashSet<>();
        Set<String> visitedCursors = new HashSet<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null
                    ? client.listTools() : client.listTools(cursor);
            for (McpSchema.Tool tool : page.tools()) {
                String callbackName = McpToolUtils.format(tool.name());
                if (!StringUtils.hasText(callbackName) || !callbackNames.add(callbackName)) {
                    throw new IllegalStateException(
                            "MCP tools must have unique Spring-compatible names: " + callbackName);
                }
                callbacks.add(SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(callbackName)
                        .build());
                catalog.add(withName(tool, callbackName));
                McpSchema.ToolAnnotations annotations = tool.annotations();
                if (annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint())) {
                    names.add(callbackName);
                }
            }
            cursor = page.nextCursor();
        } while (StringUtils.hasText(cursor) && visitedCursors.add(cursor));
        if (!catalog.isEmpty() && names.isEmpty()) {
            LOGGER.warn("MCP server declared none of its {} tools read-only;"
                    + " the server likely predates readOnlyHint annotations, so every tool"
                    + " will require change approval and specialists get no tools.", catalog.size());
        }
        ToolCallback[] discoveredCallbacks = callbacks.toArray(ToolCallback[]::new);
        return new DiscoveredTools(() -> discoveredCallbacks.clone(), Set.copyOf(names),
                List.copyOf(catalog));
    }

    private static McpSchema.Tool withName(McpSchema.Tool tool, String name) {
        return new McpSchema.Tool(name, tool.title(), tool.description(), tool.inputSchema(),
                tool.outputSchema(), tool.annotations(), tool.meta(), tool.icons());
    }

    private Duration longer(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    static void injectCurrentTrace(HttpRequest.Builder request) {
        if (!ScoreAiObservability.currentContextCanPropagate()) return;
        W3CTraceContextPropagator.getInstance().inject(
                Context.current(), request, TRACE_HEADER_SETTER);
    }

    /** Injects W3C context into MCP params._meta and captures the real JSON-RPC tool request ID. */
    static String injectTraceIntoMcpBody(String body) {
        if (!StringUtils.hasText(body) || !ScoreAiObservability.currentContextCanPropagate()
                || !Span.current().getSpanContext().isValid()) return body;
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)
                    || !(root.get("params") instanceof ObjectNode params)) return body;
            JsonNode id = root.get("id");
            if ("tools/call".equals(root.path("method").asText()) && id != null
                    && !id.isNull() && id.isValueNode()) {
                String requestId = id.isTextual() ? id.textValue() : id.toString();
                if (requestId != null && requestId.length() <= 128) {
                    Span.current().setAttribute("jsonrpc.request.id", requestId);
                }
            }
            Map<String, String> trace = new LinkedHashMap<>();
            W3CTraceContextPropagator.getInstance().inject(
                    Context.current(), trace, TRACE_META_SETTER);
            if (trace.isEmpty()) return body;
            ObjectNode meta = params.get("_meta") instanceof ObjectNode existing
                    ? existing : params.putObject("_meta");
            trace.forEach(meta::put);
            return JSON.writeValueAsString(root);
        } catch (RuntimeException | java.io.IOException ignored) {
            // Trace propagation must never change MCP request behavior.
            return body;
        }
    }

    public McpConnection connection(ScoreUser requester) {
        return connection(connectionName(), requester);
    }

    McpConnection connection(String connectionName, ScoreUser requester) {
        ScoreMcpClientProperties.Connection configured = configuredConnection(connectionName);
        if (configured == null || !StringUtils.hasText(configured.getUrl())) {
            return null;
        }
        String endpoint = StringUtils.hasText(configured.getEndpoint())
                ? configured.getEndpoint() : "/mcp";
        String token = bearerToken(connectionName, requester);
        return new McpConnection(configured.getUrl().strip(), endpoint, token);
    }

    public List<String> connectionNames() {
        return mcpProperties.connectionNames();
    }

    public String connectionName() {
        return properties.getTools().getConnectCenterMcp().getConnectionName();
    }

    private String bearerToken(ScoreUser requester) {
        return bearerToken(connectionName(), requester);
    }

    private String bearerToken(String connectionName, ScoreUser requester) {
        ScoreMcpClientProperties.Connection connection = configuredConnection(connectionName);
        if (connection == null) return null;
        ScoreMcpClientProperties.Auth auth = connection.getAuth();
        if (StringUtils.hasText(auth.getBearerToken())) {
            return auth.getBearerToken();
        }
        if (!StringUtils.hasText(auth.getIssuerUrl())) {
            return null;
        }
        long minimumTtl = Math.max(60L,
                properties.getElicitationTimeout().plusSeconds(60).toSeconds());
        return brokerJwtService.issueToken(requester, auth.getIssuerUrl(), auth.getAudience(),
                auth.getAlgorithm(), Math.max(auth.getTokenTtlSeconds(), minimumTtl));
    }

    private ScoreMcpClientProperties.Connection configuredConnection(String connectionName) {
        return mcpProperties.connection(connectionName);
    }

    void authorizeRequest(ScoreUser requester, HttpRequest.Builder request) {
        authorizeRequest(connectionName(), requester, request);
    }

    private void authorizeRequest(String connectionName, ScoreUser requester,
                                  HttpRequest.Builder request) {
        String token = bearerToken(connectionName, requester);
        if (StringUtils.hasText(token)) {
            request.setHeader("Authorization", "Bearer " + token);
        }
    }

    record DiscoveredTools(ToolCallbackProvider callbacks, Set<String> readOnlyNames,
                           List<McpSchema.Tool> catalog) { }

    public record McpSession(McpSyncClient client, ToolCallbackProvider tools,
                             Set<String> readOnlyToolNames, List<McpSchema.Tool> toolCatalog,
                             McpTelemetry telemetry) implements AutoCloseable {
        public McpSession(McpSyncClient client, ToolCallbackProvider tools,
                          Set<String> readOnlyToolNames) {
            this(client, tools, readOnlyToolNames, List.of(), McpTelemetry.EMPTY);
        }

        public McpSession(McpSyncClient client, ToolCallbackProvider tools,
                          Set<String> readOnlyToolNames, McpTelemetry telemetry) {
            this(client, tools, readOnlyToolNames, List.of(), telemetry);
        }

        public McpSession {
            toolCatalog = toolCatalog != null ? List.copyOf(toolCatalog) : List.of();
            telemetry = telemetry != null ? telemetry : McpTelemetry.EMPTY;
        }

        @Override
        public void close() {
            if (client != null) {
                client.closeGracefully();
            }
        }
    }

    /** Content-free MCP connection metadata used for semantic-convention span enrichment. */
    public record McpTelemetry(String serverName, String protocolVersion, String serverAddress,
                               long serverPort, String networkProtocolName,
                               String networkTransport) {
        public static final McpTelemetry EMPTY =
                new McpTelemetry(null, null, null, -1, null, null);

        private static McpTelemetry from(String serverName, McpConnection connection,
                                         McpSchema.InitializeResult initialized) {
            URI uri = URI.create(connection.baseUrl());
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : null;
            long port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            }
            return new McpTelemetry(serverName,
                    initialized != null ? initialized.protocolVersion() : null,
                    uri.getHost(), port,
                    "http".equals(scheme) || "https".equals(scheme) ? "http" : scheme,
                    "http".equals(scheme) || "https".equals(scheme) ? "tcp" : null);
        }
    }

    public record McpConnection(String baseUrl, String endpoint, String bearerToken) {
        public String url() {
            String base = baseUrl.replaceAll("/+$", "");
            String path = StringUtils.hasText(endpoint) ? endpoint.strip() : "/mcp";
            return base + (path.startsWith("/") ? path : "/" + path);
        }
    }
}
