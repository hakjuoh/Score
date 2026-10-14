package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import java.util.List;

public final class ClaudeHaiku55Profile extends AbstractClaude5Profile {
    public ClaudeHaiku55Profile() {
        super("claude-haiku-5-5", "Claude Haiku 5.5",
                "For high-volume, latency-sensitive tasks such as classification, extraction, and routing.",
                List.of("adaptive", "disabled"), "medium");
    }
}
