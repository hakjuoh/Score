package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import java.util.List;

public final class ClaudeFable5Profile extends AbstractClaude5Profile {
    public ClaudeFable5Profile() {
        super("claude-fable-5", "Claude Fable 5",
                "Frontier Claude model for complex reasoning and tool-driven work.",
                List.of("adaptive"));
    }
}
