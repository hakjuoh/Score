package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

public final class ClaudeMythos5Profile extends AbstractClaude5Profile {
    public ClaudeMythos5Profile() {
        super("claude-mythos-5", "Claude Mythos 5",
                "Invitation-only Claude model sharing Fable 5 specifications.",
                List.of("adaptive"));
    }
}
