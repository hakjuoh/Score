package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.CapabilityConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.NumericConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates editable model settings against one model's provider-defined limits. */
final class AiModelProfileSettingsValidator {

    private AiModelProfileSettingsValidator() {}

    static List<ReasoningEffort> validate(
            AiModelProfile profile, AiModelCatalogUpdate input) {
        AiModelProfileView view = AiModelProfileView.from(profile);
        AiModelProfileSettingsResolver.ResolvedSettings settings =
                AiModelProfileSettingsResolver.resolve(profile, input);
        validateTokenLimits(view, input, settings);
        AiModelProfileView.ModelCapabilityConstraints capabilities = view.capabilityConstraints();
        requireSupported(input.adaptiveThinking(), capabilities.adaptiveThinking().supported(),
                "Adaptive thinking");
        requireSupported(input.providerCompactionEnabled(),
                capabilities.providerCompaction().supported(), "Provider compaction");
        requireSupported(settings.reasoningOptionsEnabled(),
                capabilities.reasoningOptions().supported(), "Reasoning options");
        requireSupported(settings.outputEffortEnabled(),
                capabilities.outputEffort().supported(), "Output effort");
        requireSupported(settings.verbosityEnabled(),
                capabilities.verbosity().supported(), "Verbosity");
        requireSupported(settings.temperatureEnabled(),
                capabilities.temperature().supported(), "Temperature");
        validateTemperature(view, settings);
        validateThinking(view, input);
        validateOutputAndCache(view, input, settings);
        if (capabilities.reasoningOptions().supported()
                && !settings.reasoningOptionsEnabled()
                && input.reasoningEfforts() != null && !input.reasoningEfforts().isEmpty()) {
            throw invalid("Reasoning efforts require reasoning options to be enabled.");
        }
        return configuredEfforts(view, input.reasoningEfforts());
    }

    private static List<ReasoningEffort> configuredEfforts(
            AiModelProfileView profile,
            List<AiModelCatalogUpdate.ReasoningEffortUpdate> requestedEfforts) {
        List<AiModelCatalogUpdate.ReasoningEffortUpdate> requested =
                requestedEfforts != null ? requestedEfforts : List.of();
        Map<String, ReasoningEffort> allowed = new LinkedHashMap<>();
        profile.reasoningEfforts().forEach(effort ->
                allowed.put(normalize(effort.name()), effort));
        Set<String> names = new java.util.HashSet<>();
        long defaults = requested.stream().filter(
                AiModelCatalogUpdate.ReasoningEffortUpdate::defaultEffort).count();
        if (!requested.isEmpty() && defaults != 1) {
            throw invalid("Configured reasoning efforts require exactly one default.");
        }
        return requested.stream().map(setting -> {
            String name = normalize(setting.name());
            ReasoningEffort definition = allowed.get(name);
            if (definition == null || !names.add(name) || setting.sortOrder() < 0) {
                throw invalid("Reasoning efforts must be unique values allowed by the model profile.");
            }
            return new ReasoningEffort(definition.name(),
                    definition.displayName(), definition.description(),
                    setting.defaultEffort(), setting.sortOrder());
        }).toList();
    }

    private static void validateTokenLimits(AiModelProfileView profile, AiModelCatalogUpdate input,
                                            AiModelProfileSettingsResolver.ResolvedSettings settings) {
        AiModelProfileView.ModelConfigurationConstraints limits = profile.configurationConstraints();
        validateNumber("Context window", input.contextWindow(), limits.contextWindow());
        validateNumber("Max output tokens", longValue(input.maxTokens()),
                limits.maxOutputTokens());
        validateNumber("Output reserve tokens", input.outputReserveTokens(),
                limits.outputReserveTokens());
        validateNumber("Auto-compact threshold", input.autoCompactThresholdTokens(),
                limits.autoCompactThresholdTokens());
        validateNumber("Emergency headroom", input.emergencyHeadroomTokens(),
                limits.emergencyHeadroomTokens());
        validateNumber("Tool output token limit", input.toolOutputTokenLimit(),
                limits.toolOutputTokenLimit());
        validateNumber("Thinking token budget", longValue(input.thinkingBudgetTokens()),
                limits.thinkingBudgetTokens());
        if (settings.thinkingBudgetTokens() != null && settings.maxTokens() != null
                && settings.thinkingBudgetTokens() >= settings.maxTokens()) {
            throw invalid("Thinking token budget must be smaller than configured max output tokens.");
        }
        long reserve = required(settings.outputReserveTokens());
        long safeInput = input.contextWindow() - reserve - input.emergencyHeadroomTokens();
        long threshold = required(settings.autoCompactThresholdTokens());
        if (reserve >= input.contextWindow()
                || input.emergencyHeadroomTokens() >= input.contextWindow() - reserve
                || safeInput <= 0 || threshold > safeInput
                || input.toolOutputTokenLimit() > safeInput) {
            throw invalid("Context reserve, headroom, compaction threshold, and tool limit "
                    + "must fit the configured context window.");
        }
    }

    private static void validateTemperature(AiModelProfileView profile,
                                            AiModelProfileSettingsResolver.ResolvedSettings settings) {
        CapabilityConstraint capability = profile.capabilityConstraints().temperature();
        boolean enabled = settings.temperatureEnabled();
        if ((!capability.supported() || !enabled)
                && settings.temperature() != null) {
            throw invalid("Temperature is not enabled for this model configuration.");
        }
        var limit = profile.configurationConstraints().temperature();
        if (settings.temperature() != null && (!Double.isFinite(settings.temperature())
                || limit.minimum() == null || settings.temperature() < limit.minimum()
                || limit.maximum() == null || settings.temperature() > limit.maximum())) {
            throw invalid("Temperature is outside the range allowed by the model profile.");
        }
    }

    private static void validateThinking(AiModelProfileView profile,
                                         AiModelCatalogUpdate input) {
        List<String> modes = input.thinkingModes() != null ? input.thinkingModes().stream()
                .map(String::strip).toList() : List.of();
        Set<String> allowed = Set.copyOf(profile.thinkingModes());
        if (modes.stream().anyMatch(mode -> !StringUtils.hasText(mode)
                || !allowed.contains(mode)) || modes.stream().distinct().count() != modes.size()) {
            throw invalid("Thinking modes must be unique values allowed by the model profile.");
        }
        if (StringUtils.hasText(input.defaultThinking())
                && !modes.contains(input.defaultThinking().strip())) {
            throw invalid("Default thinking must be one of the enabled thinking modes.");
        }
        if (input.adaptiveThinking() != modes.contains("adaptive")) {
            throw invalid("Adaptive thinking and the adaptive thinking mode must be enabled together.");
        }
        NumericConstraint fixedThinking = profile.configurationConstraints().thinkingBudgetTokens();
        if (fixedThinking.maximum() != null
                && (modes.isEmpty() || !StringUtils.hasText(input.defaultThinking()))) {
            throw invalid("Fixed thinking requires at least one enabled mode and a default mode.");
        }
        if ("enabled".equals(input.defaultThinking()) && input.thinkingBudgetTokens() == null
                && fixedThinking.defaultValue() == null) {
            throw invalid("Enabled fixed thinking requires a thinking token budget.");
        }
    }

    private static void validateOutputAndCache(AiModelProfileView profile, AiModelCatalogUpdate input,
                                               AiModelProfileSettingsResolver.ResolvedSettings settings) {
        if (StringUtils.hasText(input.outputEffort())) {
            CapabilityConstraint capability = profile.capabilityConstraints().outputEffort();
            if (!capability.supported()
                    || !settings.outputEffortEnabled()) {
                throw invalid("Output effort is not enabled for this model configuration.");
            }
            Set<String> allowed = profile.reasoningEfforts().stream()
                    .map(effort -> normalize(effort.name())).collect(java.util.stream.Collectors.toSet());
            if (!allowed.contains(normalize(input.outputEffort()))) {
                throw invalid("Output effort is not allowed by the model profile.");
            }
        }
        if (StringUtils.hasText(input.cacheStrategy())
                && !input.cacheStrategy().strip().equals(profile.cacheStrategy())) {
            throw invalid("Cache strategy is not allowed by the model profile.");
        }
    }

    private static void requireSupported(Boolean configured, Boolean supported, String label) {
        requireSupported(Boolean.TRUE.equals(configured), Boolean.TRUE.equals(supported), label);
    }

    private static void requireSupported(boolean configured, boolean supported, String label) {
        if (configured && !supported) {
            throw invalid(label + " is not supported by the selected model.");
        }
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.strip().toLowerCase(Locale.ROOT) : "";
    }

    private static void validateNumber(String label, Long value,
                                       NumericConstraint constraint) {
        if (value == null) {
            if (!constraint.optional()) throw invalid(label + " is required.");
            return;
        }
        if (constraint.minimum() == null || constraint.maximum() == null
                || value < constraint.minimum() || value > constraint.maximum()) {
            throw invalid(label + " is outside the range allowed by the model profile.");
        }
    }

    private static Long longValue(Integer value) {
        return value != null ? value.longValue() : null;
    }

    private static long required(Long value) {
        if (value == null) throw invalid("A context budget profile default is required.");
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
