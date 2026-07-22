package org.oagi.score.gateway.http.api.ai_management.provider;

import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Maps Spring AI and HTTP client failures to one {@link AiProviderFailure}.
 *
 * <p>Retryability follows the HTTP contract exposed by Spring AI: request-rate and
 * server-side conditions, timeouts, and connection resets are transient, while
 * request, authentication, and entitlement errors never succeed on retry.</p>
 */
public final class AiProviderFailureClassifier {

    private static final int MAX_MESSAGE_LENGTH = 600;

    private AiProviderFailureClassifier() {
    }

    /** Returns the classification of the first provider failure in the cause chain, or null. */
    public static AiProviderFailure classify(Throwable throwable) {
        AiProviderFailure springAiFallback = null;
        for (Throwable current = throwable; current != null; current = cause(current)) {
            AiProviderFailure failure = classifySingle(current);
            if (failure != null) {
                if (current instanceof org.springframework.ai.retry.TransientAiException
                        || current instanceof org.springframework.ai.retry.NonTransientAiException) {
                    springAiFallback = failure;
                } else {
                    return failure;
                }
            }
        }
        return springAiFallback;
    }

    private static Throwable cause(Throwable current) {
        return current.getCause() == current ? null : current.getCause();
    }

    private static AiProviderFailure classifySingle(Throwable failure) {
        SpringAiProviderFailureAdapter.Details provider =
                SpringAiProviderFailureAdapter.inspect(failure);
        if (provider != null) {
            if (provider.networkFailure()) {
                return network(failure);
            }
            return service(failure, provider.statusCode(), bounded(provider.message()),
                    retryable(provider.statusCode(), provider.errorType(), provider.shouldRetry()),
                    provider.retryAfter());
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
                    retryAfter(headerValues(rest, "Retry-After")));
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
     * Request-rate and server-side statuses are transient; caller errors are terminal.
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

    private static AiProviderFailure network(Throwable failure) {
        return new AiProviderFailure(failure.getClass().getName(), 0,
                bounded(failure.getMessage()), true, null);
    }

    private static List<String> headerValues(
            org.springframework.web.client.RestClientResponseException failure, String name) {
        List<String> values = failure.getResponseHeaders() != null
                ? failure.getResponseHeaders().get(name) : null;
        return values != null ? values : List.of();
    }

    /** Retry-After arrives as delta seconds or as an HTTP date; both mean "wait this long". */
    static Duration retryAfter(List<String> values) {
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
