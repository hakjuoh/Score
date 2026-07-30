package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP client settings consumed by the requester-scoped MCP client factory. */
@ConfigurationProperties("spring.ai.mcp.client")
public class ScoreMcpClientProperties {

    private Duration requestTimeout = Duration.ofSeconds(20);
    private Duration initializationTimeout = Duration.ofSeconds(20);
    private StreamableHttp streamableHttp = new StreamableHttp();

    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(20);
    }
    public Duration getInitializationTimeout() { return initializationTimeout; }
    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout != null
                ? initializationTimeout : Duration.ofSeconds(20);
    }
    public StreamableHttp getStreamableHttp() { return streamableHttp; }
    public void setStreamableHttp(StreamableHttp streamableHttp) {
        this.streamableHttp = streamableHttp != null ? streamableHttp : new StreamableHttp();
    }

    public Connection connection(String name) {
        return name != null ? streamableHttp.getConnections().get(name) : null;
    }

    public static class StreamableHttp {
        private Map<String, Connection> connections = new LinkedHashMap<>();

        public Map<String, Connection> getConnections() { return connections; }
        public void setConnections(Map<String, Connection> connections) {
            this.connections = connections != null
                    ? new LinkedHashMap<>(connections) : new LinkedHashMap<>();
        }
    }

    public static class Connection {
        private String url;
        private String endpoint = "/mcp";
        private Auth auth = new Auth();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public Auth getAuth() { return auth; }
        public void setAuth(Auth auth) { this.auth = auth != null ? auth : new Auth(); }
    }

    public static class Auth {
        private String bearerToken;
        private String issuerUrl;
        private String audience = "connect-center-mcp";
        private String algorithm = "ES256";
        private long tokenTtlSeconds = 300;

        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
        public String getIssuerUrl() { return issuerUrl; }
        public void setIssuerUrl(String issuerUrl) { this.issuerUrl = issuerUrl; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public long getTokenTtlSeconds() { return tokenTtlSeconds; }
        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }
    }
}
