package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;

import java.util.List;

/** Claude 4.6+ profile with adaptive thinking and output configuration. */
abstract class AbstractClaude46PlusProfile extends AbstractAiModelProfile
        implements AdaptiveThinkingModelProfile, OutputEffortModelProfile, CacheModelProfile {
    private final long contextWindow;
    private final long maxOutputTokens;
    private final List<String> thinkingModes;

    protected AbstractClaude46PlusProfile(String modelKey, String providerModelName,
                                          String displayName, String description,
                                          long contextWindow, long maxOutputTokens,
                                          boolean legacySampling) {
        this(modelKey, providerModelName, displayName, description, contextWindow,
                maxOutputTokens, legacySampling, List.of("adaptive", "disabled"));
    }

    protected AbstractClaude46PlusProfile(String modelKey, String providerModelName,
                                          String displayName, String description,
                                          long contextWindow, long maxOutputTokens,
                                          boolean legacySampling, List<String> thinkingModes) {
        super(modelKey, providerModelName, displayName, description,
                AnthropicChatOptionProfiles.options(providerModelName,
                        Math.toIntExact(maxOutputTokens), legacySampling
                                ? AnthropicChatOptionSupport.claude46()
                                : AnthropicChatOptionSupport.adaptive()));
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingModes = List.copyOf(thinkingModes);
    }

    @Override public final String getProviderType() { return AiProviderType.ANTHROPIC.value(); }
    @Override public final ModelTokenConstraints getTokenConstraints() {
        return ModelTokenConstraints.standard(contextWindow, maxOutputTokens,
                maxOutputTokens, contextWindow - maxOutputTokens - 16_000L);
    }
    @Override public final CapabilityConstraint getAdaptiveThinkingCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final CapabilityConstraint getOutputEffortCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final String getDefaultOutputEffort() { return "high"; }
    @Override public final String getDefaultCacheStrategy() { return "conversation-history"; }
    @Override public List<String> getThinkingModes() { return thinkingModes; }
    @Override public final String getDefaultThinking() { return "adaptive"; }
    @Override public final List<ReasoningEffort> getOutputEfforts() {
        return List.of(
                effort("low", "Low", "Fast responses with lighter reasoning.", false, 0),
                effort("medium", "Medium", "Balanced speed and reasoning depth.", false, 1),
                effort("high", "High", "Greater reasoning depth for complex work.", true, 2),
                effort("max", "Maximum", "Maximum effort for quality-first work.", false, 3));
    }

    private static ReasoningEffort effort(String name, String displayName, String description,
                                           boolean defaultEffort, int sortOrder) {
        return new ReasoningEffort(name, displayName, description, defaultEffort, sortOrder);
    }

    protected final long maxOutputTokens() { return maxOutputTokens; }
}
