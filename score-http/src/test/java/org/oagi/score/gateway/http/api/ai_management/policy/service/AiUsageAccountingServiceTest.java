package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq.JooqAiQuotaRepository;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiUsageAccountingServiceTest {

    @Test
    void directAgentsSelectAnAllowedEffortFromTheRequestPolicySnapshot() {
        AiPolicyService policies = mock(AiPolicyService.class);
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        EffectiveAiPolicy policy = policy(Set.of("low"));
        when(policies.resolveSnapshot(any(), eq("request-1"))).thenReturn(policy);
        AiUsageAccountingService service = new AiUsageAccountingService(policies, models,
                mock(JooqAiQuotaRepository.class), new ScoreAiProperties(),
                ScoreAiObservability.noop());
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "1",
                1L, ExecutionScope.Purpose.WORKER, List.of());

        assertThat(service.resolveReasoningEffort(scope, "model-1", "high"))
                .isEqualTo("low");
    }

    @Test
    void rejectsQuotaEnforcementWithoutTheRequiredUsageLedger() {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getPolicy().setQuotaEnforcementEnabled(true);
        properties.getPolicy().setUsageLedgerEnabled(false);

        assertThatThrownBy(() -> new AiUsageAccountingService(mock(AiPolicyService.class),
                mock(ScoreAiModelRegistry.class), mock(JooqAiQuotaRepository.class), properties,
                ScoreAiObservability.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the usage ledger");
    }

    private EffectiveAiPolicy policy(Set<String> efforts) {
        ScoreAiModelRegistry.ModelDescriptor descriptor =
                new ScoreAiModelRegistry.ModelDescriptor("model-1", "Model", "", "provider",
                        true, "high", List.of(
                        new ScoreAiModelRegistry.ReasoningEffortDescriptor("low", "Low", ""),
                        new ScoreAiModelRegistry.ReasoningEffortDescriptor("high", "High", "")),
                        new ScoreAiModelRegistry.ContextBudgetDescriptor(
                                1000L, 100L, 800L, 100L, 200L, false));
        return new EffectiveAiPolicy(new UserId(BigInteger.ONE), false, true,
                List.of(new AiCatalogModel(AiModelId.from(7L), descriptor)), "model-1", true,
                4, 8, null, null, null, null, 1L,
                Map.of(AiModelId.from(7L), efforts));
    }
}
