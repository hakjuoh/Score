package org.oagi.score.gateway.http.api.ai_management.provider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads provider failures that Spring AI 2.x currently propagates from its
 * transitive model adapters. Reflection is deliberate here: application code
 * stays independent of the vendor SDK APIs while preserving status, headers,
 * and error bodies until Spring AI normalizes those exceptions itself.
 */
final class SpringAiProviderFailureAdapter {

    private static final String ANTHROPIC_ERRORS = "com.anthropic.errors";
    private static final String OPENAI_ERRORS = "com.openai.errors";

    private SpringAiProviderFailureAdapter() {
    }

    static Details inspect(Throwable failure) {
        if (failure == null || !isProviderFailure(failure.getClass())) {
            return null;
        }
        Integer statusCode = integer(invoke(failure, "statusCode"));
        if (statusCode != null) {
            Object headers = invoke(failure, "headers");
            return new Details(statusCode, message(failure), errorType(failure),
                    shouldRetry(headerValues(headers, "x-should-retry")),
                    retryAfter(headerValues(headers, "retry-after-ms"),
                            headerValues(headers, "retry-after")), false);
        }
        return isNetworkFailure(failure.getClass())
                ? new Details(0, null, null, null, null, true) : null;
    }

    private static boolean isProviderFailure(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String packageName = current.getPackageName();
            if (ANTHROPIC_ERRORS.equals(packageName) || OPENAI_ERRORS.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNetworkFailure(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getSimpleName();
            if (name.endsWith("IoException") || name.endsWith("RetryableException")) {
                return true;
            }
        }
        return false;
    }

    private static String message(Throwable failure) {
        Map<?, ?> body = jsonObject(invoke(failure, "body"));
        if (body == null) {
            return null;
        }
        Object message = body.get("message");
        if (message == null) {
            Map<?, ?> error = jsonObject(body.get("error"));
            message = error != null ? error.get("message") : null;
        }
        return jsonString(message);
    }

    private static String errorType(Throwable failure) {
        Object type = unwrap(invoke(failure, "errorType"));
        if (type == null) {
            type = unwrap(invoke(failure, "type"));
        }
        if (type instanceof String text) {
            return text;
        }
        Object value = invoke(type, "asString");
        return value instanceof String text ? text : null;
    }

    private static Map<?, ?> jsonObject(Object value) {
        Object object = unwrap(invoke(unwrap(value), "asObject"));
        return object instanceof Map<?, ?> map ? map : null;
    }

    private static String jsonString(Object value) {
        Object string = unwrap(invoke(unwrap(value), "asString"));
        return string instanceof String text ? text : null;
    }

    private static List<String> headerValues(Object headers, String name) {
        Object values = invoke(headers, "values", String.class, name);
        return values instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }

    private static Boolean shouldRetry(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        String value = values.getFirst().strip();
        return "true".equalsIgnoreCase(value) ? Boolean.TRUE
                : "false".equalsIgnoreCase(value) ? Boolean.FALSE : null;
    }

    private static Duration retryAfter(List<String> millisValues, List<String> secondsValues) {
        if (!millisValues.isEmpty()) {
            try {
                long millis = Long.parseLong(millisValues.getFirst().strip());
                if (millis > 0) {
                    return Duration.ofMillis(millis);
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the standard Retry-After header.
            }
        }
        return AiProviderFailureClassifier.retryAfter(secondsValues);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Object invoke(Object target, String name) {
        return invoke(target, name, null, null);
    }

    private static Object invoke(Object target, String name, Class<?> parameterType, Object argument) {
        if (target == null) {
            return null;
        }
        try {
            Method method = parameterType == null
                    ? target.getClass().getMethod(name)
                    : target.getClass().getMethod(name, parameterType);
            return parameterType == null ? method.invoke(target) : method.invoke(target, argument);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                 | SecurityException ignored) {
            return null;
        }
    }

    record Details(int statusCode, String message, String errorType,
                   Boolean shouldRetry, Duration retryAfter, boolean networkFailure) {
    }
}
