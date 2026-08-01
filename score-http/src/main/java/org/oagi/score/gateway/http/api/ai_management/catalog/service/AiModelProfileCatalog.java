package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelOptions;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.*;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic.*;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai.*;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of provider model profiles supported by the application.
 *
 * <p>Profile values follow the provider documentation current at the time each concrete
 * profile is updated. Review context/output limits, thinking modes, and effort values against
 * the official OpenAI and Anthropic model documentation whenever a model profile changes.</p>
 */
public final class AiModelProfileCatalog {
    private static final List<AiModelProfile> PROFILES = List.of(
            new ClaudeHaiku45Profile(),
            new ClaudeSonnet45Profile(), new ClaudeSonnet46Profile(),
            new ClaudeSonnet5Profile(),
            new ClaudeOpus45Profile(), new ClaudeOpus46Profile(),
            new ClaudeOpus47Profile(), new ClaudeOpus48Profile(), new ClaudeOpus5Profile(),
            new ClaudeFable5Profile(), new ClaudeMythos5Profile(),
            new Gpt56SolProfile(), new Gpt56TerraProfile(), new Gpt56LunaProfile(),
            new Gpt55Profile(),
            new Gpt54Profile(), new Gpt54ProProfile(),
            new Gpt54MiniProfile(), new Gpt54NanoProfile());

    private AiModelProfileCatalog() {}

    public static List<AiModelProfile> modelsFor(String providerType) {
        String normalizedProvider = AiProviderType.from(providerType).value();
        return PROFILES.stream()
                .filter(model -> model.getProviderType().equals(normalizedProvider)).toList();
    }

    public static Optional<AiModelProfile> find(String providerType, String modelKey) {
        if (modelKey == null) return Optional.empty();
        return modelsFor(providerType).stream()
                .filter(model -> model.getModelKey().equals(modelKey.strip()))
                .findFirst();
    }

    /**
     * Installs the supported model profiles into the bootstrap property graph. This deliberately
     * replaces legacy configured models so the concrete profile classes remain the only source
     * of persisted bootstrap model settings. Database-backed configuration replaces this graph
     * after catalog bootstrap.
     */
    public static void install(ScoreAiProperties properties) {
        if (properties == null) return;

        Map<AiProviderType, String> providersByType = new LinkedHashMap<>();
        properties.getProviders().forEach((name, provider) -> providerType(provider.getType())
                .ifPresent(type -> providersByType.putIfAbsent(type, name)));

        Map<String, ScoreAiProperties.Model> models = new LinkedHashMap<>();
        PROFILES.forEach(profile -> {
            String provider = providersByType.get(AiProviderType.from(profile.getProviderType()));
            if (provider != null) models.put(profile.getModelKey(), configuredModel(profile, provider));
        });
        properties.setModels(models);
    }

    private static Optional<AiProviderType> providerType(String value) {
        try {
            return Optional.of(AiProviderType.from(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static ScoreAiProperties.Model configuredModel(AiModelProfile profile,
                                                            String provider) {
        AiModelProfileView view = AiModelProfileView.from(profile);
        AiModelProfileView.ModelConfigurationConstraints values = view.configurationConstraints();
        AiModelProfileView.ModelCapabilityConstraints feature = view.capabilityConstraints();
        ScoreAiProperties.Model model = new ScoreAiProperties.Model();
        model.setDisplayName(profile.getDisplayName());
        model.setDescription(profile.getDescription());
        model.setProvider(provider);
        model.setModel(profile.getProviderModelName());
        model.setReasoningEffort(view.reasoningEfforts().stream()
                .filter(ReasoningEffort::defaultEffort)
                .map(ReasoningEffort::name).findFirst().orElse(null));
        model.setReasoningEfforts(view.reasoningEfforts().stream()
                .map(AiModelProfileCatalog::configuredEffort).toList());
        model.setMaxTokens(integer(values.maxOutputTokens().defaultValue()));
        model.setContextWindow(values.contextWindow().defaultValue());
        model.setTemperature(values.temperature().defaultValue());
        model.setThinkingBudgetTokens(integer(values.thinkingBudgetTokens().defaultValue()));
        model.setAdaptiveThinking(feature.adaptiveThinking().defaultEnabled());
        model.setOutputEffort(view.outputEffort());
        model.setCacheStrategy(view.cacheStrategy());
        model.setModelOptions(AiModelOptions.profileDefaults(profile));

        ScoreAiProperties.ContextBudget budget = new ScoreAiProperties.ContextBudget();
        budget.setOutputReserveTokens(values.outputReserveTokens().defaultValue());
        budget.setAutoCompactThresholdTokens(values.autoCompactThresholdTokens().defaultValue());
        budget.setEmergencyHeadroomTokens(values.emergencyHeadroomTokens().defaultValue());
        budget.setToolOutputTokenLimit(values.toolOutputTokenLimit().defaultValue());
        budget.setProviderCompactionEnabled(feature.providerCompaction().defaultEnabled());
        model.setContextBudget(budget);

        ScoreAiProperties.ModelCapabilities capabilities =
                new ScoreAiProperties.ModelCapabilities();
        capabilities.setReasoningModel(feature.reasoningOptions().defaultEnabled());
        capabilities.setOutputEffort(feature.outputEffort().defaultEnabled());
        capabilities.setVerbosity(feature.verbosity().defaultEnabled());
        capabilities.setTemperature(feature.temperature().defaultEnabled());
        capabilities.setThinkingModes(view.thinkingModes());
        capabilities.setDefaultThinking(view.defaultThinking());
        model.setModelCapabilities(capabilities);
        return model;
    }

    private static ScoreAiProperties.ReasoningEffort configuredEffort(
            ReasoningEffort profile) {
        ScoreAiProperties.ReasoningEffort effort = new ScoreAiProperties.ReasoningEffort();
        effort.setName(profile.name());
        effort.setDisplayName(profile.displayName());
        effort.setDescription(profile.description());
        return effort;
    }

    private static Integer integer(Long value) {
        return value != null ? Math.toIntExact(value) : null;
    }
}
