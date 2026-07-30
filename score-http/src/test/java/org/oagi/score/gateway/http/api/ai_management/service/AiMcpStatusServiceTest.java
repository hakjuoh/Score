package org.oagi.score.gateway.http.api.ai_management.service;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiMcpStatus;
import org.oagi.score.gateway.http.api.ai_management.model.AiMcpServerStatus;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMcpStatusServiceTest {

    private final ConnectCenterMcpClientFactory mcpClients =
            mock(ConnectCenterMcpClientFactory.class);
    private final ScoreUser requester = mock(ScoreUser.class);
    private final AiMcpStatusService service = new AiMcpStatusService(mcpClients);

    @Test
    void reportsEveryConfiguredServerInDeclarationOrder() {
        McpSyncClient firstClient = mock(McpSyncClient.class);
        McpSyncClient secondClient = mock(McpSyncClient.class);
        ToolCallback[] firstTools = {mock(ToolCallback.class), mock(ToolCallback.class)};
        ToolCallback[] secondTools = {mock(ToolCallback.class)};
        when(mcpClients.connectionNames()).thenReturn(List.of("first-mcp", "second-mcp"));
        when(mcpClients.openForStatus("first-mcp", requester)).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(
                        firstClient, () -> firstTools, Set.of()));
        when(mcpClients.openForStatus("second-mcp", requester)).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(
                        secondClient, () -> secondTools, Set.of()));

        AiMcpStatus status = service.check(requester);

        assertThat(status.servers()).containsExactly(
                new AiMcpServerStatus(
                        "first-mcp", AiMcpServerStatus.ConnectionState.CONNECTED, 2),
                new AiMcpServerStatus(
                        "second-mcp", AiMcpServerStatus.ConnectionState.CONNECTED, 1));
        verify(firstClient).closeGracefully();
        verify(secondClient).closeGracefully();
    }

    @Test
    void distinguishesAnUnconfiguredConnectionFromAConnectionFailure() {
        when(mcpClients.connectionNames()).thenReturn(List.of("connect-center-mcp"));
        when(mcpClients.openForStatus("connect-center-mcp", requester)).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(
                        null, () -> new ToolCallback[0], Set.of()));

        AiMcpStatus status = service.check(requester);

        assertThat(status.servers()).containsExactly(new AiMcpServerStatus(
                "connect-center-mcp", AiMcpServerStatus.ConnectionState.NOT_CONFIGURED, 0));
    }

    @Test
    void convertsABoundedStatusProbeTimeoutToASafeUnavailableStatus() {
        when(mcpClients.connectionNames()).thenReturn(List.of("healthy-mcp", "offline-mcp"));
        when(mcpClients.openForStatus("healthy-mcp", requester)).thenReturn(
                new ConnectCenterMcpClientFactory.McpSession(
                        mock(McpSyncClient.class), () -> new ToolCallback[0], Set.of()));
        when(mcpClients.openForStatus("offline-mcp", requester)).thenThrow(
                new IllegalStateException("Status probe timed out with sensitive upstream details"));

        AiMcpStatus status = service.check(requester);

        assertThat(status.servers()).containsExactly(
                new AiMcpServerStatus(
                        "healthy-mcp", AiMcpServerStatus.ConnectionState.CONNECTED, 0),
                new AiMcpServerStatus(
                        "offline-mcp", AiMcpServerStatus.ConnectionState.UNAVAILABLE, 0));
        assertThat(status.toString()).doesNotContain("sensitive upstream details");
    }

    @Test
    void returnsAnEmptyServerListWhenNoMcpServersAreDeclared() {
        when(mcpClients.connectionNames()).thenReturn(List.of());

        assertThat(service.check(requester).servers()).isEmpty();
    }
}
