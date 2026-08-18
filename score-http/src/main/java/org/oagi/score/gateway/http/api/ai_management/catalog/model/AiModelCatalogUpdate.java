package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import java.util.List;
import java.util.Map;

public record AiModelCatalogUpdate(AiProviderId providerId, String modelKey,
                                   boolean enabled, boolean defaultModel, boolean lightweightModel, int sortOrder,
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
                                   Map<String, Object> modelOptions,
                                   List<ReasoningEffortUpdate> reasoningEfforts) {
    public AiModelCatalogUpdate(AiProviderId providerId, String modelKey,
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
                                Map<String, Object> modelOptions,
                                List<ReasoningEffortUpdate> reasoningEfforts) {
        this(providerId, modelKey, enabled, defaultModel, false, sortOrder,
                maxTokens, contextWindow, outputReserveTokens, autoCompactThresholdTokens,
                emergencyHeadroomTokens, toolOutputTokenLimit, providerCompactionEnabled,
                temperature, thinkingBudgetTokens, adaptiveThinking, outputEffort,
                cacheStrategy, reasoningModelSupported, outputEffortSupported,
                verbositySupported, temperatureSupported, thinkingModes, defaultThinking,
                modelOptions, reasoningEfforts);
    }

    public AiModelCatalogUpdate(AiProviderId providerId, String modelKey,
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
        this(providerId, modelKey, enabled, defaultModel, false, sortOrder,
                maxTokens, contextWindow, outputReserveTokens, autoCompactThresholdTokens,
                emergencyHeadroomTokens, toolOutputTokenLimit, providerCompactionEnabled,
                temperature, thinkingBudgetTokens, adaptiveThinking, outputEffort,
                cacheStrategy, reasoningModelSupported, outputEffortSupported,
                verbositySupported, temperatureSupported, thinkingModes, defaultThinking,
                Map.of(), reasoningEfforts);
    }

    public AiModelCatalogUpdate(AiProviderId providerId, String modelKey,
                                boolean enabled, boolean defaultModel, boolean lightweightModel, int sortOrder,
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
        this(providerId, modelKey, enabled, defaultModel, lightweightModel, sortOrder,
                maxTokens, contextWindow, outputReserveTokens, autoCompactThresholdTokens,
                emergencyHeadroomTokens, toolOutputTokenLimit, providerCompactionEnabled,
                temperature, thinkingBudgetTokens, adaptiveThinking, outputEffort,
                cacheStrategy, reasoningModelSupported, outputEffortSupported,
                verbositySupported, temperatureSupported, thinkingModes, defaultThinking,
                Map.of(), reasoningEfforts);
    }

    public record ReasoningEffortUpdate(String name, boolean defaultEffort, int sortOrder) {}
}
