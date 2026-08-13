package org.oagi.score.gateway.http.api.activity_management.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for optional, best-effort SCORE activity event delivery. */
@ConfigurationProperties("score.activity.events")
public class ScoreActivityEventProperties {

    private boolean enabled;
    private String sink = "opentelemetry";
    private int workerCount = 1;
    private int queueCapacity = 1024;
    private Duration shutdownTimeout = Duration.ofSeconds(12);
    private final Redis redis = new Redis();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSink() {
        return sink;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public Redis getRedis() {
        return redis;
    }

    public static final class Redis {
        private String topic = "score.activity.events.v1";

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }
}
