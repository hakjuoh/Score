package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiMcpStatus;
import org.oagi.score.gateway.http.api.ai_management.model.AiMcpServerStatus;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Checks every configured MCP server without making one slow server delay the others. */
@Service
public class AiMcpStatusService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiMcpStatusService.class);

    private final ConnectCenterMcpClientFactory mcpClients;

    public AiMcpStatusService(ConnectCenterMcpClientFactory mcpClients) {
        this.mcpClients = mcpClients;
    }

    public AiMcpStatus check(ScoreUser requester) {
        List<String> serverNames = mcpClients.connectionNames();
        if (serverNames.isEmpty()) {
            return new AiMcpStatus(List.of());
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<AiMcpServerStatus>> checks = serverNames.stream()
                    .map(name -> CompletableFuture.supplyAsync(
                            () -> checkServer(name, requester), executor))
                    .toList();
            return new AiMcpStatus(checks.stream().map(CompletableFuture::join).toList());
        }
    }

    private AiMcpServerStatus checkServer(String serverName, ScoreUser requester) {
        try (ConnectCenterMcpClientFactory.McpSession session =
                     mcpClients.openForStatus(serverName, requester)) {
            if (session.client() == null) {
                return new AiMcpServerStatus(
                        serverName, AiMcpServerStatus.ConnectionState.NOT_CONFIGURED, 0);
            }
            int toolCount = session.tools().getToolCallbacks().length;
            return new AiMcpServerStatus(
                    serverName, AiMcpServerStatus.ConnectionState.CONNECTED, toolCount);
        } catch (RuntimeException exception) {
            LOGGER.warn("MCP server check failed for {} ({}).", serverName,
                    exception.getClass().getSimpleName());
            LOGGER.debug("MCP server check details for {}.", serverName, exception);
            return new AiMcpServerStatus(
                    serverName, AiMcpServerStatus.ConnectionState.UNAVAILABLE, 0);
        }
    }
}
