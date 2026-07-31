package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import java.util.List;

public record AiModelCatalogView(long aiModelId, long providerId, String provider,
                                 String modelKey, String providerModelName,
                                 String displayName, String description, boolean enabled,
                                 boolean defaultModel, int sortOrder, Integer maxTokens,
                                 long contextWindow, Long outputReserveTokens,
                                 Long autoCompactThresholdTokens, long emergencyHeadroomTokens,
                                 long toolOutputTokenLimit, boolean providerCompactionEnabled,
                                 Double temperature, Integer thinkingBudgetTokens,
                                 boolean adaptiveThinking, String outputEffort,
                                 String cacheStrategy, Boolean reasoningModelSupported,
                                 Boolean outputEffortSupported, Boolean verbositySupported,
                                 Boolean temperatureSupported, List<String> thinkingModes,
                                 String defaultThinking,
                                 long catalogVersion, List<ReasoningEffortView> reasoningEfforts) {
    public record ReasoningEffortView(String name, String displayName, String description,
                                      boolean defaultEffort, int sortOrder) {}
}
