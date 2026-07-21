package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Maps each provider SDK's exception surface to one {@link AiProviderFailure}.
 *
 * <p>Every provider expresses errors differently: the Anthropic and OpenAI Java SDKs
 * throw Stainless-generated {@code *ServiceException} hierarchies whose JSON error
 * body carries the human-readable message, Spring's HTTP clients throw status-code
 * exceptions with a raw response body, and network failures surface as IO wrappers.
 * Retryability follows the providers' own guidance: request-rate and server-side
 * conditions (429, 5xx including Anthropic's 529 overload, timeouts, connection
 * resets) are transient, while request, authentication, and entitlement errors
 * (400, 401, 403, 404, 413, 422) never succeed on retry.</p>
 */
public final class AiProviderFailureClassifier {

    private static final int MAX_MESSAGE_LENGTH = 600;

    private AiProviderFailureClassifier() {
    }

    /** Returns the classification of the first provider failure in the cause chain, or null. */
    public static AiProviderFailure classify(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = cause(current)) {
            AiProviderFailure failure = classifySingle(current);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static Throwable cause(Throwable current) {
        return current.getCause() == current ? null : current.getCause();
    }

    private static AiProviderFailure classifySingle(Throwable failure) {
        if (failure instanceof com.anthropic.errors.AnthropicServiceException anthropic) {
            return service(anthropic, anthropic.statusCode(), anthropicMessage(anthropic),
                    retryable(anthropic.statusCode(),
                            anthropic.errorType().map(com.anthropic.models.ErrorType::asString)
                                    .orElse(null),
                            shouldRetryHeader(anthropic.headers().values("x-should-retry"))),
                    retryAfter(anthropic.headers().values("retry-after-ms"),
                            anthropic.headers().values("retry-after")));
        }
        if (failure instanceof com.anthropic.errors.AnthropicIoException
                || failure instanceof com.anthropic.errors.AnthropicRetryableException) {
            return network(failure);
        }
        if (failure instanceof com.openai.errors.OpenAIServiceException openAi) {
            return service(openAi, openAi.statusCode(), openAiMessage(openAi),
                    retryable(openAi.statusCode(),
                            openAi.type().orElse(null),
                            shouldRetryHeader(openAi.headers().values("x-should-retry"))),
                    retryAfter(openAi.headers().values("retry-after-ms"),
                            openAi.headers().values("retry-after")));
        }
        if (failure instanceof com.openai.errors.OpenAIIoException
                || failure instanceof com.openai.errors.OpenAIRetryableException) {
            return network(failure);
        }
        if (failure instanceof org.springframework.ai.retry.TransientAiException) {
            return new AiProviderFailure(failure.getClass().getName(), 0,
                    bounded(failure.getMessage()), true, null);
        }
        if (failure instanceof org.springframework.ai.retry.NonTransientAiException) {
            return new AiProviderFailure(failure.getClass().getName(), 0,
                    bounded(failure.getMessage()), false, null);
        }
        if (failure instanceof org.springframework.web.client.RestClientResponseException rest) {
            int status = rest.getStatusCode().value();
            return service(rest, status, bounded(rest.getResponseBodyAsString()),
                    retryable(status, null, null),
                    retryAfter(List.of(), headerValues(rest, "Retry-After")));
        }
        if (failure instanceof org.springframework.web.reactive.function.client.WebClientResponseException web) {
            int status = web.getStatusCode().value();
            return service(web, status, bounded(web.getResponseBodyAsString()),
                    retryable(status, null, null), null);
        }
        if (failure instanceof org.springframework.web.client.ResourceAccessException
                || failure instanceof java.net.ConnectException
                || failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.net.UnknownHostException
                || failure instanceof java.util.concurrent.TimeoutException
                || failure instanceof java.io.IOException) {
            return network(failure);
        }
        return null;
    }

    private static AiProviderFailure service(Throwable failure, int statusCode, String message,
                                             boolean retryable, Duration retryAfter) {
        return new AiProviderFailure(failure.getClass().getName(), statusCode,
                StringUtils.hasText(message) ? message : bounded(failure.getMessage()),
                retryable, retryAfter);
    }

    /**
     * Mirrors the providers' own retry policy: request-rate and server-side statuses
     * (408, 409, 429, 5xx — Anthropic's 529 overload included) are transient, an
     * explicit {@code X-Should-Retry} header overrides in both directions, and a
     * mid-stream SSE error (status 200) is judged by the body's error type instead.
     */
    private static boolean retryable(int statusCode, String errorType, Boolean shouldRetry) {
        if (shouldRetry != null) {
            return shouldRetry;
        }
        if (statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500) {
            return true;
        }
        return errorType != null && switch (errorType) {
            case "overloaded_error", "rate_limit_error", "timeout_error", "api_error" -> true;
            default -> false;
        };
    }

    private static Boolean shouldRetryHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst().strip();
        return "true".equalsIgnoreCase(value) ? Boolean.TRUE
                : "false".equalsIgnoreCase(value) ? Boolean.FALSE : null;
    }

    private static AiProviderFailure network(Throwable failure) {
        return new AiProviderFailure(failure.getClass().getName(), 0,
                bounded(failure.getMessage()), true, null);
    }

    private static String anthropicMessage(com.anthropic.errors.AnthropicServiceException failure) {
        // JsonValue extends the raw JsonField supertype, which erases every inherited
        // generic signature for Java callers; a wildcard retype restores them.
        java.util.Map<String, com.anthropic.core.JsonValue> body =
                anthropicObject(failure.body());
        com.anthropic.core.JsonValue error = body != null ? body.get("error") : null;
        java.util.Map<String, com.anthropic.core.JsonValue> errorObject = anthropicObject(error);
        com.anthropic.core.JsonValue message =
                errorObject != null ? errorObject.get("message") : null;
        if (message == null) {
            return null;
        }
        com.anthropic.core.JsonField<?> field = message;
        return field.asString().map(AiProviderFailureClassifier::bounded).orElse(null);
    }

    private static java.util.Map<String, com.anthropic.core.JsonValue> anthropicObject(
            com.anthropic.core.JsonValue value) {
        if (value == null) {
            return null;
        }
        com.anthropic.core.JsonField<?> field = value;
        return field.asObject().orElse(null);
    }

    private static String openAiMessage(com.openai.errors.OpenAIServiceException failure) {
        // The OpenAI SDK serializes the error object itself as the body, so the
        // message sits at the top level; raw {"error":{...}} envelopes are the fallback.
        java.util.Map<String, com.openai.core.JsonValue> body = openAiObject(failure.body());
        com.openai.core.JsonValue message = body != null ? body.get("message") : null;
        if (message == null && body != null) {
            java.util.Map<String, com.openai.core.JsonValue> errorObject =
                    openAiObject(body.get("error"));
            message = errorObject != null ? errorObject.get("message") : null;
        }
        if (message == null) {
            return null;
        }
        com.openai.core.JsonField<?> field = message;
        return field.asString().map(AiProviderFailureClassifier::bounded).orElse(null);
    }

    private static java.util.Map<String, com.openai.core.JsonValue> openAiObject(
            com.openai.core.JsonValue value) {
        if (value == null) {
            return null;
        }
        com.openai.core.JsonField<?> field = value;
        return field.asObject().orElse(null);
    }

    private static List<String> headerValues(
            org.springframework.web.client.RestClientResponseException failure, String name) {
        List<String> values = failure.getResponseHeaders() != null
                ? failure.getResponseHeaders().get(name) : null;
        return values != null ? values : List.of();
    }

    /**
     * The millisecond header takes precedence when present; the standard header
     * arrives as delta seconds or as an HTTP date. All mean "wait this long".
     */
    private static Duration retryAfter(List<String> millisValues, List<String> secondsValues) {
        if (millisValues != null && !millisValues.isEmpty()) {
            try {
                long millis = Long.parseLong(millisValues.getFirst().strip());
                if (millis > 0) {
                    return Duration.ofMillis(millis);
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the standard header.
            }
        }
        return retryAfter(secondsValues);
    }

    /** Retry-After arrives as delta seconds or as an HTTP date; both mean "wait this long". */
    private static Duration retryAfter(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst().strip();
        try {
            long seconds = Long.parseLong(value);
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            try {
                java.time.ZonedDateTime until = java.time.ZonedDateTime.parse(
                        value, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration wait = Duration.between(
                        java.time.ZonedDateTime.now(until.getZone()), until)
                        .truncatedTo(ChronoUnit.SECONDS);
                return wait.isNegative() || wait.isZero() ? null : wait;
            } catch (java.time.format.DateTimeParseException notDate) {
                return null;
            }
        }
    }

    private static String bounded(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String normalized = message.strip().replaceAll("\\s+", " ");
        return normalized.length() <= MAX_MESSAGE_LENGTH
                ? normalized : normalized.substring(0, MAX_MESSAGE_LENGTH).stripTrailing() + "…";
    }
}
