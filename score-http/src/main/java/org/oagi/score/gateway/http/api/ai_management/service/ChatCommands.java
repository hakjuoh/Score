package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiCompactCommand;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** Canonical backend chat-command names, rendering, and parsing. */
final class ChatCommands {

    static final String COMPACT = "/compact";
    private static final int MAX_COMPACT_INSTRUCTION_CHARS = 2000;

    private ChatCommands() {
    }

    static AiCompactCommand parseCompact(String prompt) {
        String value = Objects.requireNonNullElse(prompt, "").strip();
        if (!value.regionMatches(true, 0, COMPACT, 0, COMPACT.length())) return null;
        if (value.length() > COMPACT.length()
                && !Character.isWhitespace(value.charAt(COMPACT.length()))) return null;
        String instructions = value.length() > COMPACT.length()
                ? value.substring(COMPACT.length()).strip() : "";
        if (instructions.length() > MAX_COMPACT_INSTRUCTION_CHARS) {
            throw new IllegalArgumentException("Compact instructions must not exceed "
                    + MAX_COMPACT_INSTRUCTION_CHARS + " characters.");
        }
        return new AiCompactCommand(instructions);
    }

    static String compactPrompt() {
        return COMPACT;
    }

    static String compactPrompt(String instructions) {
        return StringUtils.hasText(instructions)
                ? COMPACT + " " + instructions.strip() : COMPACT;
    }
}
