package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for AI observability exported through a private OpenTelemetry SDK. */
@ConfigurationProperties("score.ai.observability")
public class ScoreAiObservabilityProperties {

    private boolean enabled;
    private String serviceName = "score-ai";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
}
