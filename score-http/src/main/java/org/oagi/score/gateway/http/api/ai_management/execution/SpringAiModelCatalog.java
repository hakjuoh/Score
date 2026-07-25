package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModelCatalog;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider-neutral view over the configured Spring AI ChatModel catalog. */
@Component
public final class SpringAiModelCatalog implements AiModelCatalog {

    private final ScoreAiModelRegistry models;

    public SpringAiModelCatalog(ScoreAiModelRegistry models) {
        this.models = models;
    }

    @Override
    public AiModel require(String modelId) {
        var configured = models.modelConfiguration(models.resolveModelName(modelId));
        var budget = configured.contextBudget();
        return new AiModel(new AiModel.ModelId(configured.name()),
                new AiModel.ProviderId(configured.providerType()),
                // The registry does not currently publish Tool or multimodal feature
                // declarations. Do not advertise capabilities that were not configured.
                AiModel.ModelCapabilities.TEXT_ONLY,
                new AiModel.ContextWindow(budget.contextWindow(), configured.maxTokens() != null
                        ? configured.maxTokens().longValue() : budget.outputReserveTokens()));
    }

    @Override
    public AiModel defaultModel() { return require(models.modelName()); }

    @Override
    public List<AiModel> available() {
        return models.availableModels().stream().map(model -> require(model.name())).toList();
    }
}
