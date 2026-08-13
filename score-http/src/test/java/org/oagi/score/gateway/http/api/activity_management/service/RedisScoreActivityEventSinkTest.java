package org.oagi.score.gateway.http.api.activity_management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityActor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisScoreActivityEventSinkTest {

    @Test
    void publishesTheSharedJsonEnvelopeWithNoJavaTypeMetadata() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redissonClient.getTopic("score.activity.events.v1", StringCodec.INSTANCE)).thenReturn(topic);
        RedisScoreActivityEventSink sink = new RedisScoreActivityEventSink(
                redissonClient,
                objectMapper,
                "score.activity.events.v1");
        ScoreActivityEvent event = new ScoreActivityEvent(
                "1.0",
                "cc2f52bb-cffa-42c6-b673-6e25aba5ebda",
                Instant.parse("2026-08-06T12:00:00Z"),
                "acc.update",
                "SCORE_HTTP_API",
                "SUCCEEDED",
                new ScoreActivityActor("7", "developer"),
                List.of(new ScoreActivityTarget("ACC", "42", "abc", "Invoice", "PRIMARY")),
                Map.of("requestedFields", List.of("definition")),
                new ScoreActivityContext("1234567890abcdef1234567890abcdef", "1234567890abcdef"));

        sink.write(event);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(topic).publish(payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(json.path("occurredAt").asText()).isEqualTo("2026-08-06T12:00:00Z");
        assertThat(json.path("occurredAt").isTextual()).isTrue();
        assertThat(json.path("name").asText()).isEqualTo("acc.update");
        assertThat(json.path("source").asText()).isEqualTo("SCORE_HTTP_API");
        assertThat(json.at("/actor/userId").asText()).isEqualTo("7");
        assertThat(json.at("/targets/0/id").asText()).isEqualTo("42");
        assertThat(json.at("/properties/requestedFields/0").asText()).isEqualTo("definition");
        assertThat(payload.getValue()).doesNotContain("@class");

        Path schemaPath = repositoryContract("score-activity-event.schema.json");
        try (var input = Files.newInputStream(schemaPath)) {
            Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(input);
            assertThat(schema.validate(json)).isEmpty();
        }
    }

    private static Path repositoryContract(String filename) {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path moduleRelative = workingDirectory.resolve("../contracts").normalize().resolve(filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : workingDirectory.resolve("contracts").resolve(filename);
    }
}
