package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import java.util.List;

public record AiModelCatalogUpdate(Long expectedVersion, long providerId, String modelKey,
                                   boolean enabled, boolean defaultModel, int sortOrder,
                                   Integer maxTokens, long contextWindow,
                                   Long outputReserveTokens, Long autoCompactThresholdTokens,
                                   long emergencyHeadroomTokens, long toolOutputTokenLimit,
                                   boolean providerCompactionEnabled, Double temperature,
                                   Integer thinkingBudgetTokens, boolean adaptiveThinking,
                                   String outputEffort, String cacheStrategy,
                                   Boolean reasoningModelSupported,
                                   Boolean outputEffortSupported, Boolean verbositySupported,
                                   Boolean temperatureSupported, List<String> thinkingModes,
                                   String defaultThinking,
                                   List<ReasoningEffortUpdate> reasoningEfforts) {
    public record ReasoningEffortUpdate(String name, boolean defaultEffort, int sortOrder) {}
}
