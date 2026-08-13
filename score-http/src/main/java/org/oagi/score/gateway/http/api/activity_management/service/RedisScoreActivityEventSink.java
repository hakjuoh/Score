package org.oagi.score.gateway.http.api.activity_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import static java.util.Objects.requireNonNull;

/** Publishes the language-neutral JSON envelope to a Redis Pub/Sub topic. */
public final class RedisScoreActivityEventSink implements ScoreActivityEventSink {

    public static final String TYPE = "redis";

    private final RedissonClient redissonClient;
    private final ObjectWriter eventWriter;
    private final String topic;

    public RedisScoreActivityEventSink(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            String topic) {
        this.redissonClient = requireNonNull(redissonClient, "redissonClient must not be null");
        ObjectMapper eventMapper = requireNonNull(objectMapper, "objectMapper must not be null").copy();
        eventMapper.deactivateDefaultTyping();
        eventMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.eventWriter = eventMapper.writerFor(ScoreActivityEvent.class);
        this.topic = requireNonNull(topic, "topic must not be null");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void write(ScoreActivityEvent event) throws Exception {
        String payload = eventWriter.writeValueAsString(event);
        redissonClient.getTopic(topic, StringCodec.INSTANCE).publish(payload);
    }
}
