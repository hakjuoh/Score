package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;

public record AiCatalogModel(long id, ScoreAiModelRegistry.ModelDescriptor descriptor) {
}
