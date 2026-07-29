package org.oagi.score.gateway.http.api.ai_management.provider;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs one model-provider call with visible, bounded recovery. Transient provider
 * failures (rate limits, overload, network drops) are retried with exponential
 * backoff — honoring a provider-directed Retry-After — and every wait is narrated
 * to the user through a {@code provider_retry} event. When recovery is impossible
 * the provider's own error message is surfaced unchanged.
 *
 * <p>An attempt that executed a data-changing tool is never retried, even for a
 * transient failure: re-running the model could repeat the mutation.</p>
 */
@Component
public final class AiProviderRetryExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiProviderRetryExecutor.class);

    private final ScoreAiProperties.ProviderRetry settings;
    private final AiRequestRegistry requests;

    @Autowired
    public AiProviderRetryExecutor(ScoreAiProperties properties, AiRequestRegistry requests) {
        this(properties.getProviderRetry(), requests);
    }

    AiProviderRetryExecutor(ScoreAiProperties.ProviderRetry settings, AiRequestRegistry requests) {
        this.settings = settings;
        this.requests = requests;
    }

    public <T> T execute(ChatRequest request, AiTrajectoryRecorder recorder,
                         LongSupplier executedMutations, Supplier<T> attempt) {
        return execute(request != null ? request.requestId() : null,
                recorder, executedMutations, null, attempt);
    }

    public <T> T execute(ChatRequest request, AiTrajectoryRecorder recorder,
                         LongSupplier executedMutations, ExecutionState state,
                         Supplier<T> attempt) {
        return execute(request != null ? request.requestId() : null,
                recorder, executedMutations, state, attempt);
    }

    /** Executes a provider call that has no transport-level {@link ChatRequest}. */
    public <T> T execute(String requestId, AiTrajectoryRecorder recorder,
                         LongSupplier executedMutations, Supplier<T> attempt) {
        return execute(requestId, recorder, executedMutations, null, attempt);
    }

    public <T> T execute(String requestId, AiTrajectoryRecorder recorder,
                         LongSupplier executedMutations, ExecutionState state,
                         Supplier<T> attempt) {
        int maxAttempts = settings.getMaxAttempts();
        for (int attemptNumber = 1; ; attemptNumber++) {
            long mutationsBefore = executedMutations.getAsLong();
            try {
                return attempt.get();
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (RuntimeException failure) {
                AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);
                if (classified == null) {
                    throw failure;
                }
                boolean mutated = executedMutations.getAsLong() != mutationsBefore;
                if (!classified.retryable() || mutated
                        || attemptNumber >= maxAttempts || requestStopping(requestId)) {
                    throw new AiProviderException(classified, attemptNumber, mutated, failure);
                }
                Duration delay = delay(attemptNumber, classified.retryAfter());
                LOGGER.warn("AI provider call failed for request {} (attempt {}/{}); retrying in {}: {}",
                        requestId, attemptNumber, maxAttempts, delay,
                        classified.failureClass());
                recorder.providerRetry(attemptNumber, maxAttempts, delay.toMillis(),
                        classified.message(), classified.failureClass(), classified.statusCode());
                if (state != null) {
                    state.retryStarted();
                }
                sleep(delay);
                if (requestStopping(requestId)) {
                    throw new CancellationException(
                            "The request was stopped during a provider retry wait.");
                }
            }
        }
    }

    private Duration delay(int attemptNumber, Duration retryAfter) {
        double backoffMillis = settings.getInitialDelay().toMillis()
                * Math.pow(settings.getMultiplier(), attemptNumber - 1);
        long delayMillis = (long) Math.min(backoffMillis, settings.getMaxDelay().toMillis());
        if (retryAfter != null) {
            delayMillis = Math.max(delayMillis,
                    Math.min(retryAfter.toMillis(), settings.getMaxDelay().toMillis()));
        }
        return Duration.ofMillis(Math.max(0L, delayMillis));
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("The request was interrupted during a provider retry wait.");
        }
    }

    private boolean requestStopping(String requestId) {
        return requests != null && requestId != null
                && requests.shouldDiscardResult(requestId);
    }
}
