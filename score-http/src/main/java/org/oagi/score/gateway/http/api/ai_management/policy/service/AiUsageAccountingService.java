package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiCallReservation;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaWindow;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUsageSettlement;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq.JooqAiQuotaRepository;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AiUsageAccountingService {

    private final AiPolicyService policies;
    private final ScoreAiModelRegistry models;
    private final JooqAiQuotaRepository quotas;
    private final int inputSafetyPercent;
    private final boolean quotaEnforcementEnabled;
    private final boolean usageLedgerEnabled;
    private final ScoreAiObservability observability;

    public AiUsageAccountingService(AiPolicyService policies,
                                    ScoreAiModelRegistry models, JooqAiQuotaRepository quotas,
                                    ScoreAiProperties properties,
                                    ScoreAiObservability observability) {
        this.policies = policies;
        this.models = models;
        this.quotas = quotas;
        this.inputSafetyPercent = properties.getQuota().getInputSafetyPercent();
        this.quotaEnforcementEnabled = properties.getPolicy().isQuotaEnforcementEnabled();
        this.usageLedgerEnabled = properties.getPolicy().isUsageLedgerEnabled();
        if (quotaEnforcementEnabled && !usageLedgerEnabled) {
            throw new IllegalStateException(
                    "AI quota enforcement requires the usage ledger to be enabled.");
        }
        this.observability = observability != null ? observability : ScoreAiObservability.noop();
    }

    public AiCallReservation reserve(ChatRequest request, ExecutionScope scope,
                                     long estimatedInputTokens, String agentId) {
        if (!usageLedgerEnabled) return null;
        UserId userId;
        try {
            userId = new UserId(new BigInteger(scope.requesterId()));
        } catch (NumberFormatException exception) {
            throw new AiPolicyViolationException(AiPolicyErrorCode.AI_PROVIDER_NOT_CONFIGURED,
                    "The AI request owner could not be resolved for usage accounting.");
        }
        ScoreUser requester = new ScoreUser(userId, null, null, null, false,
                List.of(ScoreRole.END_USER));
        var policy = policies.resolveSnapshot(requester, scope.requestId());
        policy.requireModelAllowed(request.modelName());
        policy.requireReasoningEffortAllowed(request.modelName(), request.reasoningEffort());
        var policyModel = policy.availableModels().stream()
                .filter(candidate -> candidate.descriptor().name().equals(request.modelName()))
                .findFirst().orElseThrow(() ->
                new AiPolicyViolationException(AiPolicyErrorCode.AI_PROVIDER_NOT_CONFIGURED,
                        "The AI model is missing from the usage catalog."));
        ScoreAiModelRegistry.ModelConfiguration configuration = models.modelConfiguration(
                request.modelName(), scope.requestId());
        long modelId = configuration.catalogId() != null
                ? configuration.catalogId() : policyModel.id();
        if (modelId <= 0) {
            throw new AiPolicyViolationException(AiPolicyErrorCode.AI_PROVIDER_NOT_CONFIGURED,
                    "The AI model catalog has not been initialized.");
        }
        Integer configuredMax = configuration.maxTokens();
        Long policyMax = policy.maxOutputTokensPerCall();
        Long explicitMax = configuredMax != null && policyMax != null
                ? Math.min(configuredMax.longValue(), policyMax)
                : configuredMax != null ? configuredMax.longValue() : policyMax;
        AiQuotaWindow quotaWindow = quotaEnforcementEnabled
                ? policy.currentQuotaWindow(Instant.now()).orElse(null) : null;
        boolean quotaBounded = quotaEnforcementEnabled
                && (policy.maxTotalTokensPerRequest() != null || quotaWindow != null);
        long reservationOutput = explicitMax != null ? explicitMax
                : quotaBounded ? Integer.MAX_VALUE
                : configuration.contextBudget() != null
                && configuration.contextBudget().outputReserveTokens() != null
                ? configuration.contextBudget().outputReserveTokens() : 32768L;
        int requestedOutput = (int) Math.max(1L,
                Math.min(Integer.MAX_VALUE, reservationOutput));
        long safeInput = Math.max(1L, (long) Math.ceil(estimatedInputTokens
                * (100.0 + inputSafetyPercent) / 100.0));
        AiCallReservation reservation = quotas.reserve(UUID.randomUUID(), scope.requestId(), userId, modelId,
                scope.conversationId(), scope.purpose().name().toLowerCase(), agentId,
                safeInput, requestedOutput, explicitMax != null,
                quotaEnforcementEnabled ? policy.maxTotalTokensPerRequest() : null,
                quotaWindow);
        observability.quotaReserved(reservation.reservedTokens());
        return reservation;
    }

    /** Resolves the snapshotted default to an effort allowed by the same policy snapshot. */
    public String resolveReasoningEffort(ExecutionScope scope, String modelName,
                                         String configuredDefault) {
        UserId userId;
        try {
            userId = new UserId(new BigInteger(scope.requesterId()));
        } catch (NumberFormatException exception) {
            throw new AiPolicyViolationException(AiPolicyErrorCode.AI_PROVIDER_NOT_CONFIGURED,
                    "The AI request owner could not be resolved for policy enforcement.");
        }
        ScoreUser requester = new ScoreUser(userId, null, null, null, false,
                List.of(ScoreRole.END_USER));
        var policy = policies.resolveSnapshot(requester, scope.requestId());
        var model = policy.availableModels().stream()
                .filter(candidate -> candidate.descriptor().name().equals(modelName))
                .findFirst().orElseThrow(() -> new AiPolicyViolationException(
                        AiPolicyErrorCode.AI_MODEL_NOT_ALLOWED,
                        "The requested AI model is not allowed by the user policy: " + modelName));
        var restricted = policy.allowedReasoningEfforts().get(model.id());
        if (restricted == null || restricted.isEmpty()) {
            policy.requireReasoningEffortAllowed(modelName, configuredDefault);
            return configuredDefault;
        }
        return model.descriptor().reasoningEfforts().stream()
                .map(ScoreAiModelRegistry.ReasoningEffortDescriptor::name)
                .filter(effort -> restricted.stream().anyMatch(effort::equalsIgnoreCase))
                .findFirst().orElseThrow(() -> new AiPolicyViolationException(
                        AiPolicyErrorCode.AI_REASONING_EFFORT_NOT_ALLOWED,
                        "No allowed reasoning effort remains for model '" + modelName + "'."));
    }

    public void settle(AiCallReservation reservation, AiUsageSettlement usage) {
        if (reservation != null) {
            boolean changed = quotas.settle(reservation.callId(), usage);
            long charged = usage.complete()
                    ? Math.addExact(usage.promptTokens(), usage.completionTokens())
                    : reservation.reservedTokens();
            if (changed) observability.quotaConsumed(charged, reservation.reservedTokens());
        }
    }

    public void release(AiCallReservation reservation, Throwable failure) {
        if (reservation != null) {
            boolean changed = quotas.release(reservation.callId(),
                    failure != null ? failure.getClass().getSimpleName() : null);
            if (changed) observability.quotaReleased(reservation.reservedTokens());
        }
    }

    public boolean isQuotaExhausted(org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy policy) {
        if (!quotaEnforcementEnabled) return false;
        return policy.currentQuotaWindow(Instant.now())
                .map(window -> quotas.isExhausted(policy.userId(), window)).orElse(false);
    }
}
