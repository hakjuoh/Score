package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.CapabilityConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.OutputEffortModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;

import java.util.List;

public final class ClaudeOpus45Profile extends AbstractClaude45Profile
        implements OutputEffortModelProfile {
    public ClaudeOpus45Profile() {
        super("claude-opus-4_5", "claude-opus-4-5", "Claude Opus 4.5",
                "High-capability Claude 4.5 model for difficult reasoning and agentic work.",
                200_000L, 64_000L, AnthropicChatOptionSupport.opus45());
    }

    @Override public CapabilityConstraint getOutputEffortCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public String getDefaultOutputEffort() { return "high"; }
    @Override public List<ReasoningEffort> getOutputEfforts() {
        return AnthropicOutputEfforts.profiles(AnthropicOutputEfforts.THROUGH_HIGH);
    }
}
