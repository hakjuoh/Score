package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

public final class ClaudeSonnet5Profile extends AbstractClaude5Profile {
    public ClaudeSonnet5Profile() {
        super("claude-sonnet-5", "Claude Sonnet 5",
                "Balanced Claude model for everyday reasoning and agentic work.");
    }

    @Override
    public List<String> getThinkingModes() {
        return List.of("adaptive", "disabled");
    }
}
