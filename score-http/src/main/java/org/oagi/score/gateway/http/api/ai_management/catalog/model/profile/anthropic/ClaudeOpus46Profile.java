package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

public final class ClaudeOpus46Profile extends AbstractClaude46Profile {
    public ClaudeOpus46Profile() {
        super("claude-opus-4_6", "claude-opus-4-6", "Claude Opus 4.6",
                "High-capability Claude model with adaptive thinking and structured output.",
                1_000_000L, 128_000L);
    }
}
