package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import java.util.List;

public final class ClaudeOpus55Profile extends AbstractClaude5Profile {
    public ClaudeOpus55Profile() {
        super("claude-opus-5-5", "Claude Opus 5.5",
                "For long-running agentic coding and knowledge work.",
                List.of("adaptive"), "medium");
    }
}
