package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JooqAiCatalogBootstrapRepositoryTest {

    @Test
    void consolidatesLegacyModelSettingsAndGenericOptionsIntoOneJsonDocument() {
        ScoreAiProperties.Model model = new ScoreAiProperties.Model();
        model.setTemperature(0.4);
        model.setThinkingBudgetTokens(2048);
        model.setAdaptiveThinking(true);
        model.setOutputEffort("high");
        model.setCacheStrategy("conversation-history");
        model.setModelOptions(Map.of("topP", 0.8, "metadata", Map.of("team", "search")));
        model.getContextBudget().setProviderCompactionEnabled(false);
        model.getModelCapabilities().setReasoningModel(true);
        model.getModelCapabilities().setOutputEffort(true);
        model.getModelCapabilities().setVerbosity(false);
        model.getModelCapabilities().setTemperature(true);
        model.getModelCapabilities().setThinkingModes(List.of("adaptive", "disabled"));
        model.getModelCapabilities().setDefaultThinking("adaptive");

        Map<String, Object> json = JooqAiCatalogBootstrapRepository.modelOptions(model);

        assertThat(json).containsEntry("topP", 0.8)
                .containsEntry("temperature", 0.4)
                .containsEntry("thinkingBudgetTokens", 2048)
                .containsEntry("adaptiveThinking", true)
                .containsEntry("providerCompactionEnabled", false)
                .containsEntry("reasoningModelSupported", true)
                .containsEntry("defaultThinking", "adaptive");
        assertThat(json.get("metadata")).isEqualTo(Map.of("team", "search"));
        assertThat(json.get("thinkingModes")).isEqualTo(List.of("adaptive", "disabled"));
    }
}
