package org.oagi.score.gateway.http.api.ai_management.model;

import org.springframework.util.StringUtils;

import java.util.List;

/** User-selected policy for data-changing assistant tool calls. */
public enum AiMutationPermissionMode {

    ASK("ask"),
    AUTO_SAFE("auto"),
    FULL_ACCESS("full_access");

    private static final List<String> SAFE_MUTATION_PREFIXES = List.of(
            "create_", "add_", "assign_", "reuse_", "copy_", "import_", "upload_");

    private final String value;

    AiMutationPermissionMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean automaticallyAllows(String toolName) {
        if (this == FULL_ACCESS) {
            return true;
        }
        return this == AUTO_SAFE && StringUtils.hasText(toolName)
                && SAFE_MUTATION_PREFIXES.stream().anyMatch(toolName::startsWith);
    }

    /** Describes the active server policy precisely enough for assistant narration. */
    public String assistantPolicy() {
        return switch (this) {
            case ASK -> "ask: every data-changing tool call requires explicit user approval.";
            case AUTO_SAFE -> "auto: tools whose names begin with "
                    + String.join(", ", SAFE_MUTATION_PREFIXES)
                    + " run without approval; other data-changing tools require approval.";
            case FULL_ACCESS -> "full_access: data-changing tool calls run without approval.";
        };
    }

    public static AiMutationPermissionMode resolve(String value) {
        if (!StringUtils.hasText(value)) {
            return ASK;
        }
        String normalized = value.strip().toLowerCase();
        for (AiMutationPermissionMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Mutation permission mode must be ask, auto, or full_access.");
    }
}
