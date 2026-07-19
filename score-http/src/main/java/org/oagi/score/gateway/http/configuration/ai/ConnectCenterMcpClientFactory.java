package org.oagi.score.gateway.http.configuration.ai;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.oagi.score.gateway.http.api.application_management.service.BrokerJwtService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Creates requester-scoped MCP tools for connect-center-mcp. */
@Component
public class ConnectCenterMcpClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectCenterMcpClientFactory.class);
    private static final ToolCallbackProvider NO_TOOLS = () -> new ToolCallback[0];

    private final ScoreAiProperties properties;
    private final Environment environment;
    private final BrokerJwtService brokerJwtService;

    public ConnectCenterMcpClientFactory(ScoreAiProperties properties, Environment environment,
                                         BrokerJwtService brokerJwtService) {
        this.properties = properties;
        this.environment = environment;
        this.brokerJwtService = brokerJwtService;
    }

    public McpSession open(ScoreUser requester) {
        return open(requester, null);
    }

    public McpSession open(
            ScoreUser requester,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler) {
        McpConnection connection = connection(requester);
        if (connection == null) {
            return new McpSession(null, NO_TOOLS, Set.of());
        }
        String baseUrl = connection.baseUrl();
        String endpoint = connection.endpoint();
        String token = connection.bearerToken();

        HttpClientStreamableHttpTransport.Builder transport = HttpClientStreamableHttpTransport
                .builder(baseUrl)
                .endpoint(endpoint)
                .openConnectionOnStartup(false);
        if (StringUtils.hasText(token)) {
            transport.requestBuilder(HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + token));
        }

        var clientBuilder = McpClient.sync(transport.build())
                .requestTimeout(longer(properties.getMcp().getRequestTimeout(), properties.getRequestTimeout()))
                .initializationTimeout(properties.getMcp().getInitializationTimeout());
        if (elicitationHandler != null) {
            clientBuilder.elicitation(elicitationHandler).applyElicitationDefaults(true);
        }
        McpSyncClient client = clientBuilder.build();
        try {
            client.initialize();
            ToolCallbackProvider tools = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(List.of(client))
                    .build();
            return new McpSession(client, tools, readOnlyToolNames(client));
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
    private static Set<String> readOnlyToolNames(McpSyncClient client) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> visitedCursors = new HashSet<>();
        int toolCount = 0;
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null
                    ? client.listTools() : client.listTools(cursor);
            for (McpSchema.Tool tool : page.tools()) {
                toolCount++;
                McpSchema.ToolAnnotations annotations = tool.annotations();
                if (annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint())) {
                    names.add(tool.name());
                }
            }
            cursor = page.nextCursor();
        } while (StringUtils.hasText(cursor) && visitedCursors.add(cursor));
        if (toolCount > 0 && names.isEmpty()) {
            LOGGER.warn("connect-center-mcp declared none of its {} tools read-only;"
                    + " the server likely predates readOnlyHint annotations, so every tool"
                    + " will require mutation approval and specialists get no tools.", toolCount);
        }
        return Set.copyOf(names);
    }

    private Duration longer(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    public McpConnection connection(ScoreUser requester) {
        String name = properties.getMcp().getConnectionName();
        String prefix = "spring.ai.mcp.client.streamable-http.connections." + name;
        String baseUrl = environment.getProperty(prefix + ".url");
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String endpoint = environment.getProperty(prefix + ".endpoint", "/mcp");
        String token = bearerToken(requester);
        return new McpConnection(baseUrl.strip(), endpoint, token);
    }

    private String bearerToken(ScoreUser requester) {
        ScoreAiProperties.Auth auth = properties.getMcp().getAuth();
        if (StringUtils.hasText(auth.getBearerToken())) {
            return auth.getBearerToken();
        }
        if (!StringUtils.hasText(auth.getIssuerUrl())) {
            return null;
        }
        long minimumTtl = Math.max(60L, properties.getRequestTimeout().plusSeconds(60).toSeconds());
        return brokerJwtService.issueToken(requester, auth.getIssuerUrl(), auth.getAudience(),
                auth.getAlgorithm(), Math.max(auth.getTokenTtlSeconds(), minimumTtl));
    }

    public record McpSession(McpSyncClient client, ToolCallbackProvider tools,
                             Set<String> readOnlyToolNames) implements AutoCloseable {
        @Override
        public void close() {
            if (client != null) {
                client.closeGracefully();
            }
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
