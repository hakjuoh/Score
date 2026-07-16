package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiRequestStateStoreTest {

    @Test
    void redisCodecRoundTripsTheCompleteSharedLifecycleRecord() throws Exception {
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        AiSharedRequestState expected = new AiSharedRequestState(
                "request-1", "conversation-1", "42", "instance-a", 7L,
                now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(40)),
                now, now.plusSeconds(1), now.plusSeconds(1), null,
                "RUNNING", null, "CANCELLED", null, null, null,
                3L, true, 1, false);
        TypedJsonJacksonCodec codec = new TypedJsonJacksonCodec(
                String.class, AiSharedRequestState.class,
                new ObjectMapper().findAndRegisterModules());
        ByteBuf encoded = codec.getMapValueEncoder().encode(expected);
        try {
            Object decoded = codec.getMapValueDecoder().decode(encoded, null);
            assertThat(decoded).isEqualTo(expected);
        } finally {
            encoded.release();
        }
    }
}
