package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectiveAiPolicyTest {

    private static AiCatalogModel model(long id, String key, boolean defaultModel) {
        return new AiCatalogModel(AiModelId.from(id), new ScoreAiModelRegistry.ModelDescriptor(
                key, key, "", "provider", defaultModel, "medium",
                List.of(new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                                "low", "Low", ""),
                        new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                                "medium", "Medium", "")),
                new ScoreAiModelRegistry.ContextBudgetDescriptor(
                        1000L, 100L, 800L, 100L, 500L, true)));
    }

    @Test
    void blocksDisabledUsersAndUnavailableModelsWithTypedErrors() {
        EffectiveAiPolicy disabled = policy(false, List.of(model(1, "allowed", true)), Map.of());
        assertThatThrownBy(disabled::requireEnabled)
                .isInstanceOfSatisfying(AiPolicyViolationException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                AiPolicyErrorCode.AI_DISABLED_BY_POLICY));

        EffectiveAiPolicy enabled = policy(true, List.of(model(1, "allowed", true)), Map.of());
        assertThatThrownBy(() -> enabled.requireModelAllowed("forbidden"))
                .isInstanceOfSatisfying(AiPolicyViolationException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                AiPolicyErrorCode.AI_MODEL_NOT_ALLOWED));
    }

    @Test
    void rejectsRestrictedReasoningEffortAndClampsAgents() {
        EffectiveAiPolicy policy = policy(true, List.of(model(1, "allowed", true)),
                Map.of(AiModelId.from(1L), Set.of("low")));

        assertThatThrownBy(() -> policy.requireReasoningEffortAllowed("allowed", "medium"))
                .isInstanceOfSatisfying(AiPolicyViolationException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                AiPolicyErrorCode.AI_REASONING_EFFORT_NOT_ALLOWED));
        assertThat(policy.constrain(new AiMultiAgentOptions(true, 4, "balanced")).maxAgents())
                .isEqualTo(2);
    }

    @Test
    void alwaysUsesSingleAgentWhenMultiAgentIsDisabled() {
        EffectiveAiPolicy policy = new EffectiveAiPolicy(
                new UserId(BigInteger.ONE), false, true,
                List.of(model(1, "allowed", true)), "allowed", false,
                4, 8, null, null, null, null, 1, Map.of());
        assertThat(policy.constrain(new AiMultiAgentOptions(true, 4, "balanced")).active())
                .isFalse();
    }

    @Test
    void preservesAutomaticRoutingCapacityWithoutForcingDelegation() {
        EffectiveAiPolicy policy = policy(true, List.of(model(1, "allowed", true)), Map.of());

        AiMultiAgentOptions constrained = policy.constrain(AiMultiAgentOptions.single());

        assertThat(policy.allowsMultiAgentRouting()).isTrue();
        assertThat(constrained.active()).isFalse();
        assertThat(constrained.maxAgents()).isEqualTo(2);
    }

    private static EffectiveAiPolicy policy(boolean enabled, List<AiCatalogModel> models,
                                            Map<AiModelId, Set<String>> efforts) {
        return new EffectiveAiPolicy(new UserId(BigInteger.ONE), false, enabled,
                models, "allowed", true, 2, 8, null, null,
                null, null, 1, efforts);
    }
}
