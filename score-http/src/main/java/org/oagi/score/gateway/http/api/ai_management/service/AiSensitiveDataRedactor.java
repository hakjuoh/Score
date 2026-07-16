package org.oagi.score.gateway.http.api.ai_management.service;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Shared secret-key and free-text redaction rules for AI audit and UI surfaces. */
final class AiSensitiveDataRedactor {

    private static final String SENSITIVE_TEXT_NAME =
            "authorization|authentication|auth(?:[_-]?(?:header|token|value))?"
                    + "|api[_-]?key|access[_-]?token|refresh[_-]?token|token"
                    + "|password|secret|credentials?(?:[_-]?id)?|cookie"
                    + "|session(?:[_-]?(?:id|token|cookie))?";
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?:^|.*_)(?:authorization(?:_header|_value)?"
                    + "|authentication(?:_token|_value)?"
                    + "|auth(?:_header|_token|_value)?"
                    + "|api_key(?:_value)?|access_token(?:_value)?"
                    + "|refresh_token(?:_value)?|token(?:_value)?"
                    + "|password(?:_hash|_value)?|secret(?:_hash|_value)?"
                    + "|credential(?:s|_id|_value)?"
                    + "|cookie(?:_header|_value)?"
                    + "|session(?:_id|_token|_cookie)?)$");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(?<![a-z0-9])(" + SENSITIVE_TEXT_NAME + ")(?![a-z0-9])"
                    + "(\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\r\\n,\\\"'}]+)");

    private AiSensitiveDataRedactor() {
    }

    static boolean isSensitiveKey(String value) {
        return SENSITIVE_KEY.matcher(normalizeKey(value)).matches();
    }

    static String redactText(String value) {
        return SENSITIVE_TEXT.matcher(Objects.requireNonNullElse(value, ""))
                .replaceAll("$1$2[REDACTED]");
    }

    private static String normalizeKey(String value) {
        return Objects.requireNonNullElse(value, "")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase(Locale.ROOT);
    }
}
