package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;

import java.util.List;

abstract class AbstractClaude5Profile extends AbstractAiModelProfile
        implements AdaptiveThinkingModelProfile, OutputEffortModelProfile, CacheModelProfile {
    protected AbstractClaude5Profile(String modelKey, String displayName, String description) {
        super(modelKey, modelKey, displayName, description);
    }

    @Override public final String getProviderType() { return AiProviderType.ANTHROPIC.value(); }
    @Override public final ModelTokenConstraints getTokenConstraints() {
        return ModelTokenConstraints.standard(1_000_000L, 128_000L, 128_000L, 800_000L);
    }
    @Override public final CapabilityConstraint getAdaptiveThinkingCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final CapabilityConstraint getOutputEffortCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final String getDefaultOutputEffort() { return "high"; }
    @Override public final String getDefaultCacheStrategy() { return "conversation-history"; }
    @Override public List<String> getThinkingModes() { return List.of("adaptive"); }
    @Override public final String getDefaultThinking() { return "adaptive"; }
    @Override public final List<ReasoningEffort> getOutputEfforts() {
        return List.of(
                effort("low", "Low", "Fast responses with lighter reasoning.", false, 0),
                effort("medium", "Medium", "Balances speed and reasoning depth for everyday tasks.", false, 1),
                effort("high", "High", "Greater reasoning depth for complex problems.", true, 2),
                effort("xhigh", "Extra High", "Extended reasoning for demanding long-running work.", false, 3),
                effort("max", "Maximum", "Maximum capability without token-spending constraints.", false, 4));
    }

    private static ReasoningEffort effort(String name, String displayName, String description,
                                           boolean defaultEffort, int sortOrder) {
        return new ReasoningEffort(name, displayName, description, defaultEffort, sortOrder);
    }
}
