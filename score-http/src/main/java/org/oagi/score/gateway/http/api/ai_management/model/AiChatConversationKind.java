package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.Locale;

/** Durable execution scope represented by an AI chat conversation row. */
public enum AiChatConversationKind {
    ROOT,
    SUBAGENT,
    PARALLEL;

    public boolean isChild() {
        return this != ROOT;
    }

    /** Treats missing or future database values as a normal root conversation. */
    public static AiChatConversationKind fromStoredValue(String value) {
        if (value == null || value.isBlank()) return ROOT;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ROOT;
        }
    }
}
