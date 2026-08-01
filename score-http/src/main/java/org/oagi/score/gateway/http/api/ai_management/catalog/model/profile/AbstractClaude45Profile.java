package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;

import java.util.List;

/** Claude 4.5 profile with fixed-budget thinking and legacy sampling controls. */
abstract class AbstractClaude45Profile extends AbstractAiModelProfile
        implements FixedThinkingModelProfile, CacheModelProfile {
    private final long contextWindow;
    private final long maxOutputTokens;

    protected AbstractClaude45Profile(String modelKey, String providerModelName,
                                      String displayName, String description,
                                      long contextWindow, long maxOutputTokens) {
        super(modelKey, providerModelName, displayName, description,
                AnthropicChatOptionProfiles.options(providerModelName,
                        Math.toIntExact(maxOutputTokens),
                        AnthropicChatOptionSupport.claude45()));
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override public final String getProviderType() { return AiProviderType.ANTHROPIC.value(); }
    @Override public final ModelTokenConstraints getTokenConstraints() {
        return ModelTokenConstraints.standard(contextWindow, maxOutputTokens,
                maxOutputTokens, contextWindow - maxOutputTokens - 16_000L);
    }
    @Override public final NumericConstraint getThinkingBudgetConstraint() {
        return new NumericConstraint(4_096L, 1_024L, maxOutputTokens - 1L, true);
    }
    @Override public final String getDefaultCacheStrategy() { return "conversation-history"; }
    @Override public final List<String> getThinkingModes() {
        return List.of("enabled", "disabled");
    }
    @Override public final String getDefaultThinking() { return "disabled"; }
}
