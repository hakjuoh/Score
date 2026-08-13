package org.oagi.score.gateway.http.api.activity_management.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.service.AsyncScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.NoopScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.OpenTelemetryScoreActivityEventSink;
import org.oagi.score.gateway.http.api.activity_management.service.RedisScoreActivityEventSink;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityAspect;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventRecorder;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventSink;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ScoreActivityEventConfigurationTest {

    private final ScoreActivityEventConfiguration configuration = new ScoreActivityEventConfiguration();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ScoreActivityEventConfiguration.class)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ScoreActivityEventRecorder.class, ScoreActivityEventRecorder::new);

    @Test
    void disabledConfigurationUsesANoopPublisher() {
        ScoreActivityEventProperties properties = new ScoreActivityEventProperties();

        ScoreActivityEventPublisher publisher = configuration.scoreActivityEventPublisher(
                properties,
                List.of(new TestSink("redis")));

        assertThat(publisher).isInstanceOf(NoopScoreActivityEventPublisher.class);
        assertThat(publisher.isEnabled()).isFalse();
    }

    @Test
    void enabledConfigurationSelectsTheSinkByItsInterfaceType() {
        ScoreActivityEventProperties properties = new ScoreActivityEventProperties();
        properties.setEnabled(true);
        properties.setSink("test");

        ScoreActivityEventPublisher publisher = configuration.scoreActivityEventPublisher(
                properties,
                List.of(new TestSink("redis"), new TestSink("test")));

        assertThat(publisher).isInstanceOf(AsyncScoreActivityEventPublisher.class);
        assertThat(publisher.isEnabled()).isTrue();
        publisher.close();
    }

    @Test
    void enabledConfigurationRejectsAnUnknownSinkAtStartup() {
        ScoreActivityEventProperties properties = new ScoreActivityEventProperties();
        properties.setEnabled(true);
        properties.setSink("missing");

        assertThatThrownBy(() -> configuration.scoreActivityEventPublisher(
                properties,
                List.of(new TestSink("redis"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void disabledEventDeliveryKeepsTheAdvisorButInitializesNoSink() {
        contextRunner
                .withPropertyValues("score.activity.events.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScoreActivityAspect.class);
                    assertThat(context).doesNotHaveBean(OpenTelemetryScoreActivityEventSink.class);
                    assertThat(context).doesNotHaveBean(RedisScoreActivityEventSink.class);
                });
    }

    @Test
    void enabledConfigurationInstallsTheAopAdvisor() {
        contextRunner
                .withPropertyValues("score.activity.events.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScoreActivityAspect.class);
                    assertThat(context).hasSingleBean(OpenTelemetryScoreActivityEventSink.class);
                    assertThat(context).doesNotHaveBean(RedisScoreActivityEventSink.class);
                    assertThat(context).hasSingleBean(AsyncScoreActivityEventPublisher.class);
                });
    }

    @Test
    void redisSelectionDoesNotInitializeTheOpenTelemetrySink() {
        contextRunner
                .withPropertyValues(
                        "score.activity.events.enabled=true",
                        "score.activity.events.sink=redis")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisScoreActivityEventSink.class);
                    assertThat(context).doesNotHaveBean(OpenTelemetryScoreActivityEventSink.class);
                });
    }

    private record TestSink(String type) implements ScoreActivityEventSink {
        @Override
        public void write(ScoreActivityEvent event) {
        }
    }
}
