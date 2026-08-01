package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.*;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;

public final class ClaudeHaiku45Profile extends AbstractAiModelProfile
        implements FixedThinkingModelProfile, CacheModelProfile {
    public ClaudeHaiku45Profile() {
        super("claude-haiku-4_5", "claude-haiku-4-5", "Claude Haiku 4.5",
                "Fast and efficient Claude model for lightweight everyday tasks.",
                AnthropicChatOptionProfiles.options("claude-haiku-4-5", 64_000,
                        AnthropicChatOptionSupport.haiku45()));
    }

    @Override public String getProviderType() { return AiProviderType.ANTHROPIC.value(); }
    @Override public ModelTokenConstraints getTokenConstraints() {
        return ModelTokenConstraints.standard(200_000L, 64_000L, 64_000L, 120_000L);
    }
    @Override public NumericConstraint getThinkingBudgetConstraint() {
        return new NumericConstraint(4_096L, 1_024L, 63_999L, true);
    }
    @Override public String getDefaultCacheStrategy() { return "conversation-history"; }
    @Override public java.util.List<String> getThinkingModes() {
        return java.util.List.of("enabled", "disabled");
    }
    @Override public String getDefaultThinking() { return "disabled"; }
}
