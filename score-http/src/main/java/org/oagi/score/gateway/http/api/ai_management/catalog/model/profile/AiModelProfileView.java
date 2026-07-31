package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/** Flattened API/read-model projection assembled from the capabilities a profile implements. */
public record AiModelProfileView(
        String providerType, String modelKey, String providerModelName, String displayName,
        String description, Integer maxTokens, long contextWindow, Integer maxOutputTokens,
        long maxContextWindow, Long outputReserveTokens, Long autoCompactThresholdTokens,
        long emergencyHeadroomTokens, long toolOutputTokenLimit,
        boolean providerCompactionEnabled, Double temperature, Integer thinkingBudgetTokens,
        Integer minThinkingBudgetTokens, Integer maxThinkingBudgetTokens,
        boolean adaptiveThinking, String outputEffort, String cacheStrategy,
        Boolean reasoningModelSupported, Boolean outputEffortSupported,
        Boolean verbositySupported, Boolean temperatureSupported,
        List<String> thinkingModes, String defaultThinking,
        List<ReasoningEffort> reasoningEfforts,
        ModelConfigurationConstraints configurationConstraints,
        ModelCapabilityConstraints capabilityConstraints) {

    public static AiModelProfileView from(AiModelProfile profile) {
        ModelTokenConstraints tokens = profile.getTokenConstraints();
        ThinkingModesModelProfile thinking =
                profile instanceof ThinkingModesModelProfile value ? value : null;
        FixedThinkingModelProfile fixedThinking =
                profile instanceof FixedThinkingModelProfile value ? value : null;
        AdaptiveThinkingModelProfile adaptiveThinking =
                profile instanceof AdaptiveThinkingModelProfile value ? value : null;
        ReasoningOptionsModelProfile reasoning =
                profile instanceof ReasoningOptionsModelProfile value ? value : null;
        ReasoningEffortModelProfile efforts =
                profile instanceof ReasoningEffortModelProfile value ? value : null;
        OutputEffortModelProfile output =
                profile instanceof OutputEffortModelProfile value ? value : null;
        TemperatureModelProfile temperature =
                profile instanceof TemperatureModelProfile value ? value : null;
        VerbosityModelProfile verbosity =
                profile instanceof VerbosityModelProfile value ? value : null;
        ProviderCompactionModelProfile compaction =
                profile instanceof ProviderCompactionModelProfile value ? value : null;
        CacheModelProfile cache = profile instanceof CacheModelProfile value ? value : null;

        if (efforts != null && output != null) {
            throw new IllegalArgumentException("Reasoning effort and output effort profiles are "
                    + "mutually exclusive provider configuration models.");
        }

        NumericConstraint thinkingBudget = fixedThinking != null
                ? fixedThinking.getThinkingBudgetConstraint()
                : new NumericConstraint(null, null, null, true);
        DecimalConstraint temperatureValue = temperature != null
                ? temperature.getTemperatureConstraint() : new DecimalConstraint(null, null, null, true);
        CapabilityConstraint reasoningCapability = reasoning != null
                ? reasoning.getReasoningOptionsCapability() : CapabilityConstraint.UNSUPPORTED;
        CapabilityConstraint outputCapability = output != null
                ? output.getOutputEffortCapability() : CapabilityConstraint.UNSUPPORTED;
        CapabilityConstraint verbosityCapability = verbosity != null
                ? verbosity.getVerbosityCapability() : CapabilityConstraint.UNSUPPORTED;
        CapabilityConstraint temperatureCapability = temperature != null
                ? temperature.getTemperatureCapability() : CapabilityConstraint.UNSUPPORTED;
        CapabilityConstraint adaptiveCapability = adaptiveThinking != null
                ? adaptiveThinking.getAdaptiveThinkingCapability() : CapabilityConstraint.UNSUPPORTED;
        CapabilityConstraint compactionCapability = compaction != null
                ? compaction.getProviderCompactionCapability() : CapabilityConstraint.UNSUPPORTED;
        ModelCapabilityConstraints capabilities = new ModelCapabilityConstraints(
                reasoningCapability, outputCapability, verbosityCapability,
                temperatureCapability, adaptiveCapability, compactionCapability);
        ModelConfigurationConstraints configuration = new ModelConfigurationConstraints(
                tokens.contextWindow(), tokens.maxOutputTokens(), tokens.outputReserveTokens(),
                tokens.autoCompactThresholdTokens(), tokens.emergencyHeadroomTokens(),
                tokens.toolOutputTokenLimit(), thinkingBudget, temperatureValue);

        return new AiModelProfileView(profile.getProviderType(), profile.getModelKey(),
                profile.getProviderModelName(), profile.getDisplayName(), profile.getDescription(),
                integer(tokens.maxOutputTokens().defaultValue()), required(tokens.contextWindow().defaultValue()),
                integer(tokens.maxOutputTokens().maximum()), required(tokens.contextWindow().maximum()),
                tokens.outputReserveTokens().defaultValue(),
                tokens.autoCompactThresholdTokens().defaultValue(),
                required(tokens.emergencyHeadroomTokens().defaultValue()),
                required(tokens.toolOutputTokenLimit().defaultValue()),
                compactionCapability.defaultEnabled(), temperatureValue.defaultValue(),
                integer(thinkingBudget.defaultValue()), integer(thinkingBudget.minimum()),
                integer(thinkingBudget.maximum()), adaptiveCapability.defaultEnabled(),
                output != null ? output.getDefaultOutputEffort() : null,
                cache != null ? cache.getDefaultCacheStrategy() : null,
                reasoningCapability.supported() ? reasoningCapability.defaultEnabled() : false,
                outputCapability.supported() ? outputCapability.defaultEnabled() : false,
                verbosityCapability.supported() ? verbosityCapability.defaultEnabled() : false,
                temperatureCapability.supported() ? temperatureCapability.defaultEnabled() : false,
                thinking != null ? List.copyOf(thinking.getThinkingModes()) : List.of(),
                thinking != null ? thinking.getDefaultThinking() : null,
                efforts != null ? List.copyOf(efforts.getReasoningEfforts())
                        : output != null ? List.copyOf(output.getOutputEfforts()) : List.of(),
                configuration, capabilities);
    }

    private static long required(Long value) {
        if (value == null) throw new IllegalStateException("A required model constraint has no value.");
        return value;
    }

    private static Integer integer(Long value) {
        return value != null ? Math.toIntExact(value) : null;
    }

    public record ModelConfigurationConstraints(
            NumericConstraint contextWindow, NumericConstraint maxOutputTokens,
            NumericConstraint outputReserveTokens, NumericConstraint autoCompactThresholdTokens,
            NumericConstraint emergencyHeadroomTokens, NumericConstraint toolOutputTokenLimit,
            NumericConstraint thinkingBudgetTokens, DecimalConstraint temperature) {}

    public record ModelCapabilityConstraints(
            CapabilityConstraint reasoningOptions, CapabilityConstraint outputEffort,
            CapabilityConstraint verbosity, CapabilityConstraint temperature,
            CapabilityConstraint adaptiveThinking, CapabilityConstraint providerCompaction) {}
}
