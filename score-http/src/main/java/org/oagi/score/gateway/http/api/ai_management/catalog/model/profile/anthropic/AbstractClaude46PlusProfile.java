package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.*;

import java.util.List;

/** Claude 4.6+ profile with adaptive thinking and output configuration. */
abstract class AbstractClaude46PlusProfile extends AbstractAiModelProfile
        implements AdaptiveThinkingModelProfile, OutputEffortModelProfile, CacheModelProfile {
    private final long contextWindow;
    private final long maxOutputTokens;
    private final List<String> thinkingModes;
    private final List<String> outputEfforts;

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
        this.outputEfforts = legacySampling ? AnthropicOutputEfforts.THROUGH_MAX
                : AnthropicOutputEfforts.THROUGH_XHIGH_AND_MAX;
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
        return AnthropicOutputEfforts.profiles(outputEfforts);
    }

    protected final long maxOutputTokens() { return maxOutputTokens; }
}
