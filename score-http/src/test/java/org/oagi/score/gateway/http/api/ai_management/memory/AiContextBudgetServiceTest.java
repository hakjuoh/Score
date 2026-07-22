package org.oagi.score.gateway.http.api.ai_management.memory;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiContextBudgetServiceTest {

    @Test
    void resolvesSafeLimitAndCompactionThresholdIndependentlyFromOutputCap() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        ScoreAiModelRegistry.ModelConfiguration model = mock(ScoreAiModelRegistry.ModelConfiguration.class);
        when(model.name()).thenReturn("gpt-5_6-sol");
        when(model.contextBudget()).thenReturn(new ScoreAiModelRegistry.ContextBudgetDescriptor(
                200000L, 32768L, 150000L, 8192L, 32000L, true));
        when(models.modelConfiguration("gpt-5_6-sol")).thenReturn(model);
        AiContextBudgetService service = new AiContextBudgetService(models);

        AiContextBudget budget = service.budget("gpt-5_6-sol").orElseThrow();

        assertThat(budget.safeInputLimit()).isEqualTo(159040L);
        assertThat(budget.shouldCompact(149999L)).isFalse();
        assertThat(budget.shouldCompact(150000L)).isTrue();
        assertThat(budget.exceedsSafeInput(159040L)).isTrue();
    }

    @Test
    void estimatesUtf8AndMessageOverheadWithoutOverflowing() {
        AiContextBudgetService service = new AiContextBudgetService(mock(ScoreAiModelRegistry.class));
        long shortEstimate = service.estimateInputTokens(
                List.of(new UserMessage("hello")), null, null);
        long multilingualEstimate = service.estimateInputTokens(
                List.of(new UserMessage("data🙂".repeat(1000))), new UserMessage("next"), "page");

        assertThat(shortEstimate).isGreaterThanOrEqualTo(4096L);
        assertThat(multilingualEstimate).isGreaterThan(shortEstimate);
    }

    @Test
    void clampsUsageAtOneHundredPercentAndNeverReportsNegativeRemainingTokens() {
        AiContextBudget budget = new AiContextBudget(
                "model", 1000L, 200L, 600L, 100L, 100L, false);

        var usage = budget.usage(Long.MAX_VALUE, true, "adversarial-estimate");

        assertThat(usage.usedPercent()).isEqualTo(100.0);
        assertThat(usage.remainingTokens()).isZero();
    }
}
