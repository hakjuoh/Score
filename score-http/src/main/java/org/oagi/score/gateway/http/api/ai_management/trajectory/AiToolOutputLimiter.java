package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.model.AiBoundedToolOutput;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Owns UTF-8-safe tool-output limits and their estimated context reservation. */
final class AiToolOutputLimiter {

    private static final String TRUNCATION_SUFFIX =
            "\n[TOOL OUTPUT TRUNCATED: rerun the tool with narrower filters or pagination.]";

    private final AiContextBudget contextBudget;
    private final AtomicLong estimatedInputFloor;
    private final boolean subagentScope;

    AiToolOutputLimiter(AiContextBudget contextBudget, AtomicLong estimatedInputFloor,
                        boolean subagentScope) {
        this.contextBudget = contextBudget;
        this.estimatedInputFloor = estimatedInputFloor;
        this.subagentScope = subagentScope;
    }

    synchronized AiBoundedToolOutput reserve(String output, long configuredLimit) {
        long effectiveLimit = configuredLimit;
        if (contextBudget != null) {
            long remaining = Math.max(0L,
                    contextBudget.safeInputLimit() - estimatedInputFloor.get());
            effectiveLimit = Math.min(effectiveLimit, remaining);
        }
        AiBoundedToolOutput bounded = bounded(output, effectiveLimit);
        growEstimatedInputFloor(bounded.returnedBytes());
        return bounded;
    }

    void resetEstimatedInputFloor(long inputTokens) {
        estimatedInputFloor.set(Math.max(0L, inputTokens));
    }

    AiContextUsageInfo usageAfter(AiBoundedToolOutput bounded) {
        if (contextBudget == null || bounded.returnedBytes() <= 0 || subagentScope) return null;
        return contextBudget.usage(estimatedInputFloor.get(), true, "tool_output_estimate");
    }

    static AiBoundedToolOutput bounded(String output, long tokenLimit) {
        String source = Objects.requireNonNullElse(output, "");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        long byteLimit = tokenLimit == Long.MAX_VALUE || tokenLimit > Integer.MAX_VALUE / 3L
                ? Integer.MAX_VALUE : Math.max(0L, tokenLimit) * 3L;
        if (bytes.length <= byteLimit) {
            return new AiBoundedToolOutput(source, false, bytes.length, bytes.length);
        }
        int maximumBytes = (int) byteLimit;
        int suffixBytes = TRUNCATION_SUFFIX.getBytes(StandardCharsets.UTF_8).length;
        if (maximumBytes <= suffixBytes) {
            String marker = TRUNCATION_SUFFIX.substring(
                    0, Math.min(maximumBytes, TRUNCATION_SUFFIX.length()));
            return new AiBoundedToolOutput(marker, true, bytes.length,
                    marker.getBytes(StandardCharsets.UTF_8).length);
        }
        int prefixBudget = Math.max(0, maximumBytes - suffixBytes);
        int chars = 0;
        int usedBytes = 0;
        while (chars < source.length()) {
            int codePoint = source.codePointAt(chars);
            int width = Character.charCount(codePoint);
            int encoded = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + encoded > prefixBudget) break;
            chars += width;
            usedBytes += encoded;
        }
        String bounded = source.substring(0, chars) + TRUNCATION_SUFFIX;
        return new AiBoundedToolOutput(bounded, true, bytes.length,
                bounded.getBytes(StandardCharsets.UTF_8).length);
    }

    private void growEstimatedInputFloor(int utf8Bytes) {
        long additional = utf8Bytes <= 0 ? 0L : (utf8Bytes + 2L) / 3L;
        estimatedInputFloor.updateAndGet(current -> current > Long.MAX_VALUE - additional
                ? Long.MAX_VALUE : current + additional);
    }
}
