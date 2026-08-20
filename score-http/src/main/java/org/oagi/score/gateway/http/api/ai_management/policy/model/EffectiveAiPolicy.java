package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Immutable, request-scoped result of merging catalog state with one user's policy. */
public record EffectiveAiPolicy(
        UserId userId,
        boolean inherited,
        boolean aiEnabled,
        List<AiCatalogModel> availableModels,
        String defaultModelKey,
        boolean multiAgentEnabled,
        int maxAgentsPerRequest,
        int maxActiveRequests,
        Long maxOutputTokensPerCall,
        Long maxTotalTokensPerRequest,
        AiQuotaPeriod quotaPeriod,
        Long quotaTokens,
        long policyVersion,
        Map<AiModelId, Set<String>> allowedReasoningEfforts) {

    public EffectiveAiPolicy {
        availableModels = availableModels != null ? List.copyOf(availableModels) : List.of();
        allowedReasoningEfforts = allowedReasoningEfforts != null
                ? allowedReasoningEfforts.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue()))) : Map.of();
        if (maxAgentsPerRequest < 1 || maxAgentsPerRequest > 4) {
            throw new IllegalArgumentException("Maximum agents must be between 1 and 4.");
        }
        if (maxActiveRequests < 1 || maxActiveRequests > 32) {
            throw new IllegalArgumentException("Maximum active requests must be between 1 and 32.");
        }
    }

    public void requireEnabled() {
        if (!aiEnabled) {
            throw new AiPolicyViolationException(AiPolicyErrorCode.AI_DISABLED_BY_POLICY,
                    "AI Assistant is disabled by the user policy.");
        }
        if (availableModels.isEmpty()) {
            throw new AiPolicyViolationException(AiPolicyErrorCode.AI_NO_ALLOWED_MODELS,
                    "No AI models are allowed by the user policy.");
        }
    }

    public String resolveDefaultModel() {
        requireEnabled();
        return availableModels.stream()
                .map(model -> model.descriptor().name())
                .filter(name -> name.equals(defaultModelKey))
                .findFirst()
                .orElse(availableModels.getFirst().descriptor().name());
    }

    public void requireModelAllowed(String model) {
        requireEnabled();
        String requested = model != null ? model.strip() : resolveDefaultModel();
        if (availableModels.stream().noneMatch(entry ->
                entry.descriptor().name().equals(requested))) {
            throw new AiPolicyViolationException(AiPolicyErrorCode.AI_MODEL_NOT_ALLOWED,
                    "The requested AI model is not allowed by the user policy: " + requested);
        }
    }

    public void requireReasoningEffortAllowed(String model, String effort) {
        if (effort == null || effort.isBlank()) return;
        requireModelAllowed(model);
        AiCatalogModel catalogModel = availableModels.stream()
                .filter(entry -> entry.descriptor().name().equals(model)).findFirst().orElseThrow();
        Set<String> allowed = allowedReasoningEfforts.get(catalogModel.id());
        if (allowed != null && !allowed.isEmpty()
                && allowed.stream().noneMatch(value -> value.equalsIgnoreCase(effort.strip()))) {
            throw new AiPolicyViolationException(
                    AiPolicyErrorCode.AI_REASONING_EFFORT_NOT_ALLOWED,
                    "The requested reasoning effort is not allowed for model '" + model + "': "
                            + effort.strip());
        }
    }

    public AiMultiAgentOptions constrain(AiMultiAgentOptions requested) {
        AiMultiAgentOptions value = requested != null ? requested : AiMultiAgentOptions.single();
        if (!allowsMultiAgentRouting()) return AiMultiAgentOptions.single();
        int maximum = Math.min(Math.min(value.maxAgents(), maxAgentsPerRequest),
                AiMultiAgentOptions.MAX_AGENTS);
        return new AiMultiAgentOptions(value.active(), maximum, value.strategy());
    }

    /** Whether automatic routing may create a multi-Agent Workflow for this user. */
    public boolean allowsMultiAgentRouting() {
        return multiAgentEnabled && maxAgentsPerRequest >= AiMultiAgentOptions.MIN_AGENTS;
    }

    public OptionalLong maxOutputTokensPerCallLimit() {
        return maxOutputTokensPerCall != null ? OptionalLong.of(maxOutputTokensPerCall)
                : OptionalLong.empty();
    }

    public OptionalLong maxTotalTokensPerRequestLimit() {
        return maxTotalTokensPerRequest != null ? OptionalLong.of(maxTotalTokensPerRequest)
                : OptionalLong.empty();
    }

    public Optional<AiQuotaWindow> currentQuotaWindow(Instant now) {
        if (quotaPeriod == null || quotaTokens == null) return Optional.empty();
        return Optional.of(new AiQuotaWindowCalculator().calculate(quotaPeriod, quotaTokens, now));
    }
}
