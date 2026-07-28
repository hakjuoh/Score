package org.oagi.score.gateway.http.api.ai_management.model;

import org.springframework.util.StringUtils;

/** User-selected policy for data-changing assistant tool calls. */
public enum AiMutationPermissionMode {

    ASK("ask"),
    AUTO_SAFE("auto"),
    FULL_ACCESS("full_access");

    private final String value;

    AiMutationPermissionMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * Reports whether this mode runs a tool of the given risk class without asking, judged from
     * the risk class alone. An owner-scoped tool is not settled here: it additionally requires
     * the requester to own the target, which {@link #requiresOwnershipCheck(AiMutationRisk)} asks for.
     */
    public boolean automaticallyAllows(AiMutationRisk risk) {
        if (this == FULL_ACCESS) {
            return true;
        }
        return this == AUTO_SAFE && risk == AiMutationRisk.UNRESTRICTED;
    }

    /** Reports whether approval can still be skipped once the requester is shown to own the target. */
    public boolean requiresOwnershipCheck(AiMutationRisk risk) {
        return this == AUTO_SAFE && risk == AiMutationRisk.OWNER_SCOPED;
    }

    /** Describes the active server policy precisely enough for assistant narration. */
    public String assistantPolicy() {
        return switch (this) {
            case ASK -> "ask: every data-changing tool call requires explicit user approval.";
            case AUTO_SAFE -> "auto: creating new data runs without approval, and changing data the"
                    + " user owns runs without approval; changing data owned by somebody else"
                    + " requires approval, and so does every deletion, discard, cancellation,"
                    + " removal, state change, and ownership transfer.";
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
