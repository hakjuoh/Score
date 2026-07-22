package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider-neutral view over the configured Spring AI ChatModel catalog. */
@Component
public final class SpringAiModelCatalog {

    private final ScoreAiModelRegistry models;

    public SpringAiModelCatalog(ScoreAiModelRegistry models) {
        this.models = models;
    }

    public AiModel require(String modelId) {
        var configured = models.modelConfiguration(models.resolveModelName(modelId));
        var budget = configured.contextBudget();
        return new AiModel(new AiModel.ModelId(configured.name()),
                new AiModel.ProviderId(configured.providerType()),
                new AiModel.ModelCapabilities(true, true, true, true),
                new AiModel.ContextWindow(budget.contextWindow(), configured.maxTokens() != null
                        ? configured.maxTokens().longValue() : budget.outputReserveTokens()));
    }

    public AiModel defaultModel() { return require(models.modelName()); }

    public List<AiModel> available() {
        return models.availableModels().stream().map(model -> require(model.name())).toList();
    }
}
