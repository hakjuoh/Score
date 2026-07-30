package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for AI observability exported through a private OpenTelemetry SDK. */
@ConfigurationProperties("score.ai.observability")
public class ScoreAiObservabilityProperties {

    private boolean enabled;
    private String serviceName = "score-ai";
    private final Webhook webhook = new Webhook();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public Webhook getWebhook() { return webhook; }

    /** Outbound delivery of the same content-minimized events consumed by OpenTelemetry. */
    public static final class Webhook {
        private boolean enabled;
        private String endpoint;
        private String secret;
        private String keyId = "default";
        private boolean allowInsecureHttp;
        private boolean allowPrivateNetwork;
        private int queueCapacity = 1024;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration timeout = Duration.ofSeconds(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public boolean isAllowInsecureHttp() { return allowInsecureHttp; }
        public void setAllowInsecureHttp(boolean allowInsecureHttp) {
            this.allowInsecureHttp = allowInsecureHttp;
        }
        public boolean isAllowPrivateNetwork() { return allowPrivateNetwork; }
        public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
            this.allowPrivateNetwork = allowPrivateNetwork;
        }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }
}
