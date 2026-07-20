package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisAiRequestStateStoreIntegrationTest {

    @Autowired
    private AiRequestStateStore stateStore;

    @Test
    void roundTripsSharedStateAndDeliversCrossInstanceStopSignalsThroughRedis() throws Exception {
        String requestId = "integration-" + UUID.randomUUID();
        Instant now = Instant.now();
        AiSharedRequestState expected = new AiSharedRequestState(
                requestId, "conversation-" + UUID.randomUUID(), "42", "instance-a", 7L,
                now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(40)),
                now, now, null, null, "REGISTERED", null, "CANCELLED",
                null, null, null, 0L, false, 0, false);
        CountDownLatch stopReceived = new CountDownLatch(1);
        stateStore.addStopListener(signal -> {
            if (requestId.equals(signal.requestId()) && signal.generation() == 7L) {
                stopReceived.countDown();
            }
        });

        try {
            stateStore.withGlobalLock(storage -> {
                storage.put(expected);
                return null;
            });

            AiSharedRequestState stored = stateStore.withRequestLock(
                    requestId, storage -> storage.get(requestId));
            assertThat(stored).isEqualTo(expected);
            stateStore.publishStop(requestId, 7L);
            assertThat(stopReceived.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            stateStore.withRequestLock(requestId, storage -> {
                storage.remove(requestId);
                return null;
            });
        }
    }
}
