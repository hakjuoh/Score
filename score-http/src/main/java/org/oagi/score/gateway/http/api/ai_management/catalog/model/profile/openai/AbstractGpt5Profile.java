package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.*;

import java.util.List;

abstract class AbstractGpt5Profile extends AbstractAiModelProfile
        implements ReasoningOptionsModelProfile, ReasoningEffortModelProfile,
        VerbosityModelProfile, ProviderCompactionModelProfile {
    private final OpenAiModelProfileSpec spec;

    protected AbstractGpt5Profile(String modelKey, String providerModelName,
                                  String displayName, String description,
                                  OpenAiModelProfileSpec spec) {
        super(modelKey, providerModelName, displayName, description,
                OpenAiChatOptionProfiles.options(providerModelName, spec));
        this.spec = spec;
    }

    @Override public final String getProviderType() { return AiProviderType.OPENAI.value(); }
    @Override public final ModelTokenConstraints getTokenConstraints() {
        long compactAt = spec.contextWindow() - spec.maxOutputTokens() - 72_000L;
        return ModelTokenConstraints.standard(spec.contextWindow(), spec.maxOutputTokens(),
                spec.maxOutputTokens(), compactAt);
    }
    @Override public final CapabilityConstraint getReasoningOptionsCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final CapabilityConstraint getVerbosityCapability() {
        return new CapabilityConstraint(true, true);
    }
    @Override public final CapabilityConstraint getProviderCompactionCapability() {
        return new CapabilityConstraint(spec.providerCompaction(), false);
    }
    @Override public final List<ReasoningEffort> getReasoningEfforts() {
        return spec.reasoningEfforts();
    }
    @Override public final boolean isChatCompletionsCompatible() {
        return spec.chatCompletions();
    }
}
