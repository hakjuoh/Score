package org.oagi.score.gateway.http.api.activity_management.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityAspect;
import org.oagi.score.gateway.http.api.activity_management.service.AsyncScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.NoopScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.OpenTelemetryScoreActivityContextProvider;
import org.oagi.score.gateway.http.api.activity_management.service.OpenTelemetryScoreActivityEventSink;
import org.oagi.score.gateway.http.api.activity_management.service.RedisScoreActivityEventSink;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityContextProvider;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventRecorder;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventSink;
import org.oagi.score.gateway.http.api.activity_management.trace.ScoreActivityTracing;
import org.oagi.score.gateway.http.api.activity_management.trace.ScoreTraceContextPropagator;
import org.oagi.score.gateway.http.configuration.observability.ScoreOpenTelemetryManagementProperties;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        ScoreActivityEventProperties.class,
        ScoreOpenTelemetryManagementProperties.class})
public class ScoreActivityEventConfiguration {

    @Bean
    public ScoreActivityContextProvider scoreActivityContextProvider() {
        return new OpenTelemetryScoreActivityContextProvider();
    }

    @Bean
    public ScoreActivityEventFactory scoreActivityEventFactory(ScoreActivityContextProvider contextProvider) {
        return new ScoreActivityEventFactory(Clock.systemUTC(), contextProvider);
    }

    @Bean
    public ScoreActivityAspect scoreActivityAspect(
            ApplicationContext applicationContext,
            ScoreActivityEventPublisher publisher,
            ScoreActivityEventRecorder recorder,
            ScoreActivityTracing tracing) {
        return new ScoreActivityAspect(applicationContext, publisher, recorder, tracing);
    }

    @Bean
    public ScoreActivityTracing scoreActivityTracing() {
        return new ScoreActivityTracing();
    }

    @Bean
    public ScoreTraceContextPropagator scoreTraceContextPropagator() {
        return new ScoreTraceContextPropagator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "score.activity.events", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "score.activity.events", name = "sink", havingValue = "redis")
    public RedisScoreActivityEventSink redisScoreActivityEventSink(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            ScoreActivityEventProperties properties) {
        return new RedisScoreActivityEventSink(
                redissonClient,
                objectMapper,
                properties.getRedis().getTopic());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "score.activity.events", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "score.activity.events", name = "sink", havingValue = "opentelemetry", matchIfMissing = true)
    public OpenTelemetryScoreActivityEventSink openTelemetryScoreActivityEventSink(
            ObjectMapper objectMapper,
            ScoreActivityEventProperties activityProperties,
            ScoreOpenTelemetryManagementProperties management,
            ObjectProvider<BuildProperties> buildProperties) {
        var otlpExport = management.getOpentelemetry().getTracing().getExport().getOtlp();
        var resourceAttributes = new LinkedHashMap<>(
                management.getOpentelemetry().getResourceAttributes());
        resourceAttributes.putIfAbsent("service.name", "score");
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null) {
            resourceAttributes.putIfAbsent("service.version", build.getVersion());
        }
        return new OpenTelemetryScoreActivityEventSink(
                objectMapper,
                otlpExport.getEndpoint(),
                otlpExport.getConnectTimeout(),
                otlpExport.getTimeout(),
                management.getOpentelemetry().getTracing().getExport().getScheduleDelay(),
                activityProperties.getQueueCapacity(),
                otlpExport.getHeaders(),
                resourceAttributes);
    }

    @Bean(destroyMethod = "close")
    public ScoreActivityEventPublisher scoreActivityEventPublisher(
            ScoreActivityEventProperties properties,
            List<ScoreActivityEventSink> sinks) {
        if (!properties.isEnabled()) {
            return new NoopScoreActivityEventPublisher();
        }

        ScoreActivityEventSink selectedSink = sinks.stream()
                .filter(sink -> sink.type().equalsIgnoreCase(properties.getSink()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No SCORE activity event sink is registered for '" + properties.getSink() + "'."));
        return new AsyncScoreActivityEventPublisher(
                selectedSink,
                properties.getWorkerCount(),
                properties.getQueueCapacity(),
                properties.getShutdownTimeout());
    }
}
