package org.oagi.score.gateway.http.api.ai_management.catalog.repository;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;

import java.util.List;
import java.util.Optional;

public interface AiModelCatalogRepository {

    List<AiModelCatalogView> findAll();

    Optional<AiModelCatalogView> findById(AiModelId modelId);

    Optional<String> findEnabledProviderType(AiProviderId providerId);

    AiModelCatalogView create(UserId actorUserId, String providerType,
                              AiModelCatalogUpdate input, AiModelProfile profile,
                              List<ReasoningEffort> reasoningEfforts);

    AiModelCatalogView update(UserId actorUserId, AiModelId modelId, String providerType,
                              AiModelCatalogUpdate input, AiModelProfile profile,
                              List<ReasoningEffort> reasoningEfforts);

    ActiveCatalog findActiveCatalog();

    record ActiveModel(AiModelId id, String modelKey) {}

    record ActiveCatalog(List<ActiveModel> models, String defaultModelKey) {
        public ActiveCatalog {
            models = List.copyOf(models);
        }
    }
}
