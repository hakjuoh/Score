package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

/** Model whose extended thinking is controlled by a fixed token budget. */
public interface FixedThinkingModelProfile extends ThinkingModesModelProfile {
    NumericConstraint getThinkingBudgetConstraint();
}
