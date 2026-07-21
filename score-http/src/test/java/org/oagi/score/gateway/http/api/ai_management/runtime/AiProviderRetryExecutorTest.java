package org.oagi.score.gateway.http.api.ai_management.runtime;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProviderRetryExecutorTest {

    @Test
    void retriesATransientFailureAndNarratesTheWait() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AtomicInteger attempts = new AtomicInteger();
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(5), null);

        String answer = executor.execute(request(), recorder, () -> 0L, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new TransientAiException("503 - overloaded");
            }
            return "answer";
        });

        assertThat(answer).isEqualTo("answer");
        assertThat(attempts.get()).isEqualTo(2);
        verify(recorder).providerRetry(eq(1), eq(5), anyLong(),
                eq("503 - overloaded"), eq(TransientAiException.class.getName()), eq(0));
    }

    @Test
    void surfacesTheProviderMessageWhenAttemptsAreExhausted() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(3), null);

        assertThatThrownBy(() -> executor.execute(request(), recorder, () -> 0L, () -> {
            throw new TransientAiException("503 - overloaded");
        }))
                .isInstanceOfSatisfying(AiProviderException.class, failure -> {
                    assertThat(failure.attempts()).isEqualTo(3);
                    assertThat(failure.getMessage())
                            .contains("503 - overloaded", "failed after 3 attempts");
                })
                .cause().isInstanceOf(TransientAiException.class);
        verify(recorder, times(2)).providerRetry(
                anyInt(), anyInt(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void doesNotRetryNonTransientFailures() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(5), null);

        assertThatThrownBy(() -> executor.execute(request(), recorder, () -> 0L, () -> {
            throw new NonTransientAiException("400 - invalid request");
        }))
                .isInstanceOfSatisfying(AiProviderException.class, failure -> {
                    assertThat(failure.attempts()).isEqualTo(1);
                    assertThat(failure.getMessage()).contains("400 - invalid request")
                            .doesNotContain("attempts");
                });
        verify(recorder, never()).providerRetry(
                anyInt(), anyInt(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void neverReplaysAnAttemptThatExecutedAMutation() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AtomicLong mutations = new AtomicLong();
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(5), null);

        assertThatThrownBy(() -> executor.execute(request(), recorder, mutations::get, () -> {
            mutations.incrementAndGet();
            throw new TransientAiException("connection dropped mid-stream");
        }))
                .isInstanceOfSatisfying(AiProviderException.class, failure -> {
                    assertThat(failure.attempts()).isEqualTo(1);
                    assertThat(failure.mutationApplied()).isTrue();
                    assertThat(failure.getMessage())
                            .contains("Data changes that already completed remain applied.");
                });
        verify(recorder, never()).providerRetry(
                anyInt(), anyInt(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void honorsTheProviderDirectedWait() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AtomicInteger attempts = new AtomicInteger();
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(5), null);

        executor.execute(request(), recorder, () -> 0L, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw RateLimitException.builder()
                        .headers(Headers.builder().put("retry-after-ms", "20").build())
                        .body(JsonValue.from(Map.of("type", "error", "error",
                                Map.of("type", "rate_limit_error", "message", "Rate limited."))))
                        .build();
            }
            return "answer";
        });

        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(recorder).providerRetry(eq(1), eq(5), delay.capture(),
                eq("Rate limited."), eq(RateLimitException.class.getName()), eq(429));
        assertThat(delay.getValue()).isGreaterThanOrEqualTo(20L);
    }

    @Test
    void abortsWhenTheRequestIsStopping() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        when(requests.shouldDiscardResult("request-1")).thenReturn(true);
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(5), requests);

        assertThatThrownBy(() -> executor.execute(request(), recorder, () -> 0L, () -> {
            throw new TransientAiException("503 - overloaded");
        }))
                .isInstanceOf(AiProviderException.class);
        verify(recorder, never()).providerRetry(
                anyInt(), anyInt(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void rethrowsUnknownAndCancellationFailuresUntouched() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiProviderRetryExecutor executor = new AiProviderRetryExecutor(settings(5), null);
        IllegalStateException unknown = new IllegalStateException("empty response");
        CancellationException cancelled = new CancellationException("stopped");

        assertThatThrownBy(() -> executor.execute(request(), recorder, () -> 0L, () -> {
            throw unknown;
        })).isSameAs(unknown);
        assertThatThrownBy(() -> executor.execute(request(), recorder, () -> 0L, () -> {
            throw cancelled;
        })).isSameAs(cancelled);
        verify(recorder, never()).providerRetry(
                anyInt(), anyInt(), anyLong(), anyString(), anyString(), anyInt());
    }

    private ScoreAiProperties.ProviderRetry settings(int maxAttempts) {
        ScoreAiProperties.ProviderRetry retry = new ScoreAiProperties.ProviderRetry();
        retry.setMaxAttempts(maxAttempts);
        retry.setInitialDelay(Duration.ofMillis(1));
        retry.setMaxDelay(Duration.ofMillis(50));
        return retry;
    }

    private ChatRequest request() {
        return new ChatRequest("Investigate", "request-1", null, "conversation-1", null,
                List.of(), null, "model", "high", "default", Map.of(), "ask");
    }
}
