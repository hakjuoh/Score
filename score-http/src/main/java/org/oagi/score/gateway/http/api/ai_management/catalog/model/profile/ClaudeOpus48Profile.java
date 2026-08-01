package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class ClaudeOpus48Profile extends AbstractClaude46PlusProfile {
    public ClaudeOpus48Profile() {
        super("claude-opus-4_8", "claude-opus-4-8", "Claude Opus 4.8",
                "Advanced Claude Opus model for long-context reasoning and agentic work.",
                1_000_000L, 128_000L, false);
    }
}
