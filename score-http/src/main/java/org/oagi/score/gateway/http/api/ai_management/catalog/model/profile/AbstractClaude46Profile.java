package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/** Claude 4.6 supports adaptive thinking and deprecated fixed-budget thinking. */
abstract class AbstractClaude46Profile extends AbstractClaude46PlusProfile
        implements FixedThinkingModelProfile {
    protected AbstractClaude46Profile(String modelKey, String providerModelName,
                                      String displayName, String description,
                                      long contextWindow, long maxOutputTokens) {
        super(modelKey, providerModelName, displayName, description, contextWindow,
                maxOutputTokens, true, List.of("enabled", "adaptive", "disabled"));
    }

    @Override public final NumericConstraint getThinkingBudgetConstraint() {
        return new NumericConstraint(4_096L, 1_024L, maxOutputTokens() - 1L, true);
    }
}
