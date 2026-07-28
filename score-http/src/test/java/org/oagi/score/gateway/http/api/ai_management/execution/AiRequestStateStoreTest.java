package org.oagi.score.gateway.http.api.ai_management.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiSharedRequestState;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void runsTheCriticalSectionOfAnInterruptedCallerAndRestoresItsInterrupt() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        AiRequestStateStore store = redisStore(lock);
        AtomicBoolean executed = new AtomicBoolean();

        Thread.currentThread().interrupt();
        try {
            String outcome = store.withRequestLock("request-1", storage -> {
                executed.set(!Thread.currentThread().isInterrupted());
                return "done";
            });

            assertThat(outcome).isEqualTo("done");
            assertThat(executed).as("the withheld interrupt must not abort Redis commands").isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(lock).unlock();
    }

    @Test
    void acquiresWithABoundedWaitAndLeaseSoALostUnlockCannotWedgeLaterRequests() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

        redisStore(lock).withGlobalLock(storage -> null);

        verify(lock).tryLock(5000L, 30000L, TimeUnit.MILLISECONDS);
        verify(lock, never()).lock();
        verify(lock, never()).lock(anyLong(), any());
    }

    @Test
    void failsFastWhenTheLockStaysHeldSoTheCallerStillReachesATerminalOutcome() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        AiRequestStateStore store = redisStore(lock);

        assertThatExceptionOfType(AiSharedStateUnavailableException.class)
                .isThrownBy(() -> store.withRequestLock("request-1", storage -> "unreachable"));
        verify(lock, never()).unlock();
    }

    @Test
    void releasesTheLockWhenTheCriticalSectionFails() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        AiRequestStateStore store = redisStore(lock);

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                () -> store.withRequestLock("request-1", storage -> {
                    throw new IllegalArgumentException("boom");
                }));
        verify(lock).unlock();
    }

    @Test
    void keepsTheOperationOutcomeWhenTheReleaseItselfFails() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.getName()).thenReturn("score:ai:request-state:request-lock:request-1");
        org.mockito.Mockito.doThrow(new IllegalStateException("connection lost")).when(lock).unlock();

        String outcome = redisStore(lock).withRequestLock("request-1", storage -> "done");

        assertThat(outcome).isEqualTo("done");
    }

    @SuppressWarnings("unchecked")
    private AiRequestStateStore redisStore(RLock lock) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(redisson.getMapCache(anyString(), any(Codec.class))).thenReturn(mock(RMapCache.class));
        when(redisson.getTopic(anyString(), any(Codec.class))).thenReturn(mock(RTopic.class));
        return new RedisAiRequestStateStore(redisson, new ObjectMapper());
    }
}
