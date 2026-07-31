package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.CapabilityConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.DecimalConstraint;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.NumericConstraint;

/** Resolves nullable admin settings to the exact values validated, persisted, and run. */
final class AiModelProfileSettingsResolver {

    private AiModelProfileSettingsResolver() {}

    static ResolvedSettings resolve(AiModelProfile profile, AiModelCatalogUpdate input) {
        AiModelProfileView view = AiModelProfileView.from(profile);
        AiModelProfileView.ModelConfigurationConstraints values = view.configurationConstraints();
        AiModelProfileView.ModelCapabilityConstraints capabilities = view.capabilityConstraints();
        return new ResolvedSettings(
                integer(input.maxTokens(), values.maxOutputTokens()),
                input.contextWindow(),
                number(input.outputReserveTokens(), values.outputReserveTokens()),
                number(input.autoCompactThresholdTokens(), values.autoCompactThresholdTokens()),
                input.emergencyHeadroomTokens(), input.toolOutputTokenLimit(),
                input.providerCompactionEnabled(),
                decimal(input.temperature(), values.temperature()),
                integer(input.thinkingBudgetTokens(), values.thinkingBudgetTokens()),
                input.adaptiveThinking(),
                capability(input.reasoningModelSupported(), capabilities.reasoningOptions()),
                capability(input.outputEffortSupported(), capabilities.outputEffort()),
                capability(input.verbositySupported(), capabilities.verbosity()),
                capability(input.temperatureSupported(), capabilities.temperature()));
    }

    static Long number(Number value, NumericConstraint constraint) {
        if (value != null) return value.longValue();
        return constraint.defaultValue();
    }

    static Integer integer(Number value, NumericConstraint constraint) {
        Long resolved = number(value, constraint);
        return resolved != null ? Math.toIntExact(resolved) : null;
    }

    static Double decimal(Number value, DecimalConstraint constraint) {
        if (value != null) return value.doubleValue();
        return constraint.defaultValue();
    }

    static boolean capability(Boolean value, CapabilityConstraint constraint) {
        return value != null ? value : constraint.defaultEnabled();
    }

    record ResolvedSettings(Integer maxTokens, long contextWindow,
                            Long outputReserveTokens, Long autoCompactThresholdTokens,
                            long emergencyHeadroomTokens, long toolOutputTokenLimit,
                            boolean providerCompactionEnabled, Double temperature,
                            Integer thinkingBudgetTokens, boolean adaptiveThinking,
                            boolean reasoningOptionsEnabled, boolean outputEffortEnabled,
                            boolean verbosityEnabled, boolean temperatureEnabled) {}
}
