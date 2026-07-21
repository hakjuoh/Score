package org.oagi.score.gateway.http.api.ai_management.runtime;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderFailureClassifierTest {

    private static final String RATE_LIMIT_MESSAGE =
            "This request would exceed your rate limit tier of 50,000,000 input tokens per minute"
                    + " (org: 7ad4d13c-f824-4f0c-b02d-bd563f157670, model: claude-fable-5)."
                    + " Reduce the prompt length or the maximum tokens requested, or try again later.";

    @Test
    void extractsTheAnthropicRateLimitMessageAndHonorsRetryAfter() {
        RateLimitException failure = RateLimitException.builder()
                .headers(Headers.builder().put("retry-after", "30").build())
                .body(JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "rate_limit_error", "message", RATE_LIMIT_MESSAGE),
                        "request_id", "req_011CdEQ7rz9cnA2bgf6ZRqEa")))
                .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(
                new RuntimeException("reactive wrapper", failure));

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isEqualTo(429);
        assertThat(classified.retryable()).isTrue();
        assertThat(classified.message()).isEqualTo(RATE_LIMIT_MESSAGE);
        assertThat(classified.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(classified.failureClass()).isEqualTo(RateLimitException.class.getName());
    }

    @Test
    void prefersTheMillisecondRetryAfterHeader() {
        RateLimitException failure = RateLimitException.builder()
                .headers(Headers.builder()
                        .put("retry-after-ms", "2500")
                        .put("retry-after", "30")
                        .build())
                .body(JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "rate_limit_error", "message", "Rate limited."))))
                .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.retryAfter()).isEqualTo(Duration.ofMillis(2500));
    }

    @Test
    void treatsAnthropicOverloadAsRetryable() {
        InternalServerException failure = InternalServerException.builder()
                .statusCode(529)
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "overloaded_error", "message", "Overloaded"))))
                .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isEqualTo(529);
        assertThat(classified.retryable()).isTrue();
        assertThat(classified.message()).isEqualTo("Overloaded");
    }

    @Test
    void neverRetriesAnInvalidRequest() {
        BadRequestException failure = BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "invalid_request_error",
                                "message", "max_tokens must be positive"))))
                .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.retryable()).isFalse();
        assertThat(classified.message()).isEqualTo("max_tokens must be positive");
    }

    @Test
    void honorsAnExplicitShouldRetryOverride() {
        InternalServerException failure = InternalServerException.builder()
                .statusCode(500)
                .headers(Headers.builder().put("x-should-retry", "false").build())
                .body(JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "api_error", "message", "Broken"))))
                .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.retryable()).isFalse();
    }

    @Test
    void classifiesNetworkFailuresAsRetryable() {
        AiProviderFailure classified = AiProviderFailureClassifier.classify(
                new AnthropicIoException("Request failed", new java.io.IOException("connection reset")));

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isZero();
        assertThat(classified.retryable()).isTrue();
    }

    @Test
    void extractsTheOpenAiMessageFromTheErrorObjectBody() {
        com.openai.errors.RateLimitException failure = com.openai.errors.RateLimitException.builder()
                .headers(com.openai.core.http.Headers.builder().build())
                .error(com.openai.models.ErrorObject.builder()
                        .message("Rate limit reached for gpt-5.6 on tokens per min.")
                        .type("tokens")
                        .code(Optional.empty())
                        .param(Optional.empty())
                        .build())
                .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isEqualTo(429);
        assertThat(classified.retryable()).isTrue();
        assertThat(classified.message())
                .isEqualTo("Rate limit reached for gpt-5.6 on tokens per min.");
    }

    @Test
    void classifiesSpringRetryMarkers() {
        AiProviderFailure transientFailure = AiProviderFailureClassifier.classify(
                new TransientAiException("503 - overloaded"));
        AiProviderFailure permanentFailure = AiProviderFailureClassifier.classify(
                new NonTransientAiException("400 - invalid request"));

        assertThat(transientFailure).isNotNull();
        assertThat(transientFailure.retryable()).isTrue();
        assertThat(permanentFailure).isNotNull();
        assertThat(permanentFailure.retryable()).isFalse();
    }

    @Test
    void returnsNullForUnknownFailures() {
        assertThat(AiProviderFailureClassifier.classify(
                new IllegalStateException("The assistant returned an empty response."))).isNull();
    }
}
