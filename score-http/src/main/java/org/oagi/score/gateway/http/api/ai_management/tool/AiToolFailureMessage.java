package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts narrowly classified, user-actionable validation details from MCP failures. */
public final class AiToolFailureMessage {

    public static final String GENERIC_MESSAGE = "The tool could not complete the request.";
    private static final int MAX_RAW_CHARS = 16_384;
    private static final int MAX_PUBLIC_CHARS = 1_000;
    private static final Pattern MCP_TEXT_CONTENT = Pattern.compile(
            "\\AError calling tool:\\s*\\[TextContent\\[.*?\\btext=(.*?),\\s*meta=.*?]]\\z",
            Pattern.DOTALL);
    private static final Pattern SAFE_FIELD_VALIDATION = Pattern.compile(
            "(?iu)^`?([\\p{L}_][\\p{L}\\p{N}_.\\-\\[\\]]{0,127})`?\\s+"
                    + "(?:must(?:\\s+not)?|is\\s+required|cannot|should)\\b"
                    + "[\\p{L}\\p{N}\\p{Zs}`_.,;()\\[\\]?!+\\-]*$");
    private static final Pattern INTERNAL_OR_SECRET_DETAIL = Pattern.compile(
            "(?iu)(?:\\b(?:sql|select|insert|update|delete|from|where|database|schema|table|column|"
                    + "jdbc|odbc|stack\\s*trace|exception|traceback|authorization|authentication|"
                    + "bearer|oauth|jwt|api[_-]?key|access[_-]?token|refresh[_-]?token|password|"
                    + "secret|credentials?|cookie|session[_-]?id|authenticated|unauthenticated|"
                    + "authorized|unauthorized|forbidden|login|permission|internal|server|filesystem|"
                    + "file\\s+path)\\b|"
                    + "(?:sk|pk|rk)-[a-z0-9_-]{8,}|"
                    + "AKIA[A-Z0-9]{16}|"
                    + "gh[pousr]_[a-z0-9]{16,}|"
                    + "xox[baprs]-[a-z0-9-]{12,}|"
                    + "eyJ[a-z0-9_-]{8,}\\.[a-z0-9_-]{8,}(?:\\.[a-z0-9_-]{8,})?|"
                    + "\\b(?=[a-z0-9_-]{24,}\\b)(?=[a-z0-9_-]*[a-z])"
                    + "(?=[a-z0-9_-]*[0-9])[a-z0-9_-]+\\b|"
                    + "(?:[a-z]:\\\\|/(?:[a-z0-9_.-]+/)+)|"
                    + "\\bat\\s+[a-z0-9_.$]+\\s*\\()",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private AiToolFailureMessage() {
    }

    public static String userMessage(Throwable failure) {
        ToolExecutionException toolFailure = findToolExecutionException(failure);
        if (toolFailure == null) {
            return GENERIC_MESSAGE;
        }
        for (Throwable candidate = toolFailure.getCause(); candidate != null;
             candidate = candidate.getCause()) {
            String extracted = extractMcpText(boundRaw(candidate.getMessage()));
            if (isDisplayableValidation(extracted)) {
                return extracted.strip();
            }
        }
        return GENERIC_MESSAGE;
    }

    private static ToolExecutionException findToolExecutionException(Throwable failure) {
        for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof ToolExecutionException toolExecutionException) {
                return toolExecutionException;
            }
        }
        return null;
    }

    private static String extractMcpText(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        int contentStart = message.indexOf("TextContent[");
        if (contentStart < 0 || contentStart != message.lastIndexOf("TextContent[")) {
            return null;
        }
        Matcher matcher = MCP_TEXT_CONTENT.matcher(message);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean isDisplayableValidation(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String stripped = message.strip();
        if (stripped.codePointCount(0, stripped.length()) > MAX_PUBLIC_CHARS
                || INTERNAL_OR_SECRET_DETAIL.matcher(stripped).find()
                || !AiSensitiveDataRedactor.redactText(stripped).equals(stripped)) {
            return false;
        }
        Matcher validation = SAFE_FIELD_VALIDATION.matcher(stripped);
        return validation.matches()
                && !AiSensitiveDataRedactor.isSensitiveKey(validation.group(1));
    }

    private static String boundRaw(String message) {
        if (message == null || message.length() <= MAX_RAW_CHARS) {
            return message;
        }
        int end = MAX_RAW_CHARS;
        if (Character.isHighSurrogate(message.charAt(end - 1))
                && Character.isLowSurrogate(message.charAt(end))) {
            end--;
        }
        return message.substring(0, end);
    }
}
