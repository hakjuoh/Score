package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

/** Model whose provider can choose its thinking budget adaptively. */
public interface AdaptiveThinkingModelProfile extends ThinkingModesModelProfile {
    CapabilityConstraint getAdaptiveThinkingCapability();
}
