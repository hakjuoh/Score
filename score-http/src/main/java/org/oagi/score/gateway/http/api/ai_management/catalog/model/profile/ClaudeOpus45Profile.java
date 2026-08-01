package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class ClaudeOpus45Profile extends AbstractClaude45Profile {
    public ClaudeOpus45Profile() {
        super("claude-opus-4_5", "claude-opus-4-5", "Claude Opus 4.5",
                "High-capability Claude 4.5 model for difficult reasoning and agentic work.",
                200_000L, 64_000L);
    }
}
