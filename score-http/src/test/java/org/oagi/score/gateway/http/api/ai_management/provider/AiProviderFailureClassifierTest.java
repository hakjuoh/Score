package org.oagi.score.gateway.http.api.ai_management.provider;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderFailureClassifierTest {

    private static final String RATE_LIMIT_MESSAGE =
            "This request would exceed your rate limit tier. Try again later.";

    @Test
    void extractsAnthropicRateLimitDetailsPropagatedBySpringAi() {
        RateLimitException failure = RateLimitException.builder()
                .headers(Headers.builder().put("retry-after", "30").build())
                .body(JsonValue.from(Map.of(
                        "type", "error",
                        "error", Map.of("type", "rate_limit_error",
                                "message", RATE_LIMIT_MESSAGE))))
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
    void prefersAnthropicMillisecondRetryAfterHeader() {
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
    void neverRetriesAnthropicInvalidRequests() {
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
    void honorsProviderShouldRetryOverride() {
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
    void classifiesAnthropicIoFailuresAsRetryable() {
        AiProviderFailure classified = AiProviderFailureClassifier.classify(
                new AnthropicIoException("Request failed",
                        new java.io.IOException("connection reset")));

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isZero();
        assertThat(classified.retryable()).isTrue();
    }

    @Test
    void extractsOpenAiRateLimitDetailsPropagatedBySpringAi() {
        com.openai.errors.RateLimitException failure =
                com.openai.errors.RateLimitException.builder()
                        .headers(com.openai.core.http.Headers.builder()
                                .put("retry-after", "9").build())
                        .error(com.openai.models.ErrorObject.builder()
                                .message("Rate limit reached for the configured model.")
                                .type("rate_limit_error")
                                .code(Optional.empty())
                                .param(Optional.empty())
                                .build())
                        .build();

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isEqualTo(429);
        assertThat(classified.retryable()).isTrue();
        assertThat(classified.message())
                .isEqualTo("Rate limit reached for the configured model.");
        assertThat(classified.retryAfter()).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void classifiesSpringAiRetryMarkers() {
        AiProviderFailure transientFailure = AiProviderFailureClassifier.classify(
                new RuntimeException("wrapper", new TransientAiException("503 - overloaded")));
        AiProviderFailure permanentFailure = AiProviderFailureClassifier.classify(
                new NonTransientAiException("400 - invalid request"));

        assertThat(transientFailure).isNotNull();
        assertThat(transientFailure.retryable()).isTrue();
        assertThat(transientFailure.message()).isEqualTo("503 - overloaded");
        assertThat(permanentFailure).isNotNull();
        assertThat(permanentFailure.retryable()).isFalse();
    }

    @Test
    void classifiesHttpRateLimitsAndHonorsRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limited", headers,
                "Rate limit reached".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isEqualTo(429);
        assertThat(classified.retryable()).isTrue();
        assertThat(classified.message()).isEqualTo("Rate limit reached");
        assertThat(classified.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void prefersHttpDetailsWrappedBySpringAi() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "12");
        HttpClientErrorException cause = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limited", headers,
                "Provider quota reached".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        AiProviderFailure classified = AiProviderFailureClassifier.classify(
                new TransientAiException("429 from model provider", cause));

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isEqualTo(429);
        assertThat(classified.message()).isEqualTo("Provider quota reached");
        assertThat(classified.retryAfter()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void neverRetriesInvalidHttpRequests() {
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad request", HttpHeaders.EMPTY,
                "max_tokens must be positive".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        AiProviderFailure classified = AiProviderFailureClassifier.classify(failure);

        assertThat(classified).isNotNull();
        assertThat(classified.retryable()).isFalse();
        assertThat(classified.message()).isEqualTo("max_tokens must be positive");
    }

    @Test
    void classifiesNetworkFailuresAsRetryable() {
        AiProviderFailure classified = AiProviderFailureClassifier.classify(
                new ResourceAccessException("connection reset"));

        assertThat(classified).isNotNull();
        assertThat(classified.statusCode()).isZero();
        assertThat(classified.retryable()).isTrue();
    }

    @Test
    void returnsNullForUnknownFailures() {
        assertThat(AiProviderFailureClassifier.classify(
                new IllegalStateException("empty response"))).isNull();
    }
}
