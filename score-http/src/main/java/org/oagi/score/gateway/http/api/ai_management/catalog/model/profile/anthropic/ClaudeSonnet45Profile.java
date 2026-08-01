package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

public final class ClaudeSonnet45Profile extends AbstractClaude45Profile {
    public ClaudeSonnet45Profile() {
        super("claude-sonnet-4_5", "claude-sonnet-4-5", "Claude Sonnet 4.5",
                "Balanced Claude 4.5 model for reasoning, coding, and agentic work.",
                200_000L, 64_000L);
    }
}
