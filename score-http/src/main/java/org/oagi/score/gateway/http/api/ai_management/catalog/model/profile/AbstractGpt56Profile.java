package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;

import java.util.List;

abstract class AbstractGpt56Profile extends AbstractAiModelProfile
        implements ReasoningOptionsModelProfile, ReasoningEffortModelProfile,
        VerbosityModelProfile, ProviderCompactionModelProfile {
    protected AbstractGpt56Profile(String modelKey, String providerModelName,
                                   String displayName, String description) {
        super(modelKey, providerModelName, displayName, description);
    }

    @Override public final String getProviderType() { return AiProviderType.OPENAI.value(); }
    @Override public final ModelTokenConstraints getTokenConstraints() {
        return ModelTokenConstraints.standard(1_050_000L, 128_000L, 128_000L, 850_000L);
    }
    @Override public final CapabilityConstraint getReasoningOptionsCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final CapabilityConstraint getVerbosityCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final CapabilityConstraint getProviderCompactionCapability() {
        return new CapabilityConstraint(true, false);
    }
    @Override public final List<ReasoningEffort> getReasoningEfforts() {
        return List.of(
                effort("disabled", "Disabled", "Disable reasoning for latency-critical requests.", false, 0),
                effort("low", "Low", "Fast responses with lighter reasoning.", false, 1),
                effort("medium", "Medium", "Balances speed and reasoning depth for everyday tasks.", true, 2),
                effort("high", "High", "Greater reasoning depth for complex problems.", false, 3),
                effort("xhigh", "Extra High", "Deep reasoning for the most complex agentic work.", false, 4),
                effort("max", "Maximum", "Maximum reasoning for the hardest quality-first workloads.", false, 5));
    }

    private static ReasoningEffort effort(String name, String displayName, String description,
                                           boolean defaultEffort, int sortOrder) {
        return new ReasoningEffort(name, displayName, description, defaultEffort, sortOrder);
    }

}
