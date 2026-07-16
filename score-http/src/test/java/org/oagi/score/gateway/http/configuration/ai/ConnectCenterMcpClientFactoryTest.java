package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.application_management.service.BrokerJwtService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectCenterMcpClientFactoryTest {

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
