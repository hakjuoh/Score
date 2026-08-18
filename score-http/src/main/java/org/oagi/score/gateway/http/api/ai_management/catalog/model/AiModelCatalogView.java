package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import java.util.List;
import java.util.Map;
import java.time.Instant;

public record AiModelCatalogView(AiModelId aiModelId, AiProviderId providerId, String provider,
                                 String modelKey, String providerModelName,
                                 String displayName, String description, boolean enabled,
                                 boolean defaultModel, boolean lightweightModel, int sortOrder, Integer maxTokens,
                                 long contextWindow, Long outputReserveTokens,
                                 Long autoCompactThresholdTokens, long emergencyHeadroomTokens,
                                 long toolOutputTokenLimit, boolean providerCompactionEnabled,
                                 Double temperature, Integer thinkingBudgetTokens,
                                 boolean adaptiveThinking, String outputEffort,
                                 String cacheStrategy, Boolean reasoningModelSupported,
                                 Boolean outputEffortSupported, Boolean verbositySupported,
                                 Boolean temperatureSupported, List<String> thinkingModes,
                                 String defaultThinking,
                                 Map<String, Object> modelOptions,
                                 List<ReasoningEffortView> reasoningEfforts,
                                 String updaterLoginId, Instant lastUpdatedAt) {
    public record ReasoningEffortView(String name, String displayName, String description,
                                      boolean defaultEffort, int sortOrder) {}
}
