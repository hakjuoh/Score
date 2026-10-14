package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import java.util.List;

public final class ClaudeSonnet55Profile extends AbstractClaude5Profile {
    public ClaudeSonnet55Profile() {
        super("claude-sonnet-5-5", "Claude Sonnet 5.5",
                "The best combination of speed and intelligence.",
                List.of("adaptive"), "high");
    }
}
