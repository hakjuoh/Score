package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiModelCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class AiModelCatalogAdminService {

    private final RepositoryFactory repositoryFactory;
    private final AiAdminPolicyService authorization;
    private final ObjectMapper objectMapper;

    public AiModelCatalogAdminService(RepositoryFactory repositoryFactory,
                                      AiAdminPolicyService authorization,
                                      ObjectMapper objectMapper) {
        this.repositoryFactory = repositoryFactory;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    public List<AiModelCatalogView> list(ScoreUser actor) {
        authorization.requireAdministrator(actor);
        return repository().findAll();
    }

    public AiModelCatalogView get(ScoreUser actor, AiModelId modelId) {
        authorization.requireAdministrator(actor);
        return repository().findById(modelId).orElseThrow(NotFoundException::new);
    }

    public AiModelCatalogView create(ScoreUser actor, AiModelCatalogUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input, false);
        AiModelCatalogRepository repository = repository();
        String providerType = enabledProviderType(repository, input.providerId());
        AiModelProfile profile = requireModelProfile(providerType, input.modelKey());
        List<ReasoningEffort> efforts = AiModelProfileSettingsValidator.validate(profile, input);
        return repository.create(actor.userId(), providerType, input, profile, efforts);
    }

    public AiModelCatalogView update(ScoreUser actor, AiModelId modelId,
                                     AiModelCatalogUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input, true);
        AiModelCatalogRepository repository = repository();
        AiModelCatalogView existing = repository.findById(modelId)
                .orElseThrow(NotFoundException::new);
        if (!existing.modelKey().equals(input.modelKey().strip())) {
            throw new IllegalArgumentException("The model key is immutable.");
        }
        String providerType = enabledProviderType(repository, input.providerId());
        AiModelProfile profile = requireModelProfile(providerType, input.modelKey());
        List<ReasoningEffort> efforts = AiModelProfileSettingsValidator.validate(profile, input);
        return repository.update(actor.userId(), modelId, providerType,
                input, profile, efforts);
    }

    private AiModelCatalogRepository repository() {
        return repositoryFactory.aiModelCatalogRepository(objectMapper);
    }

    private static String enabledProviderType(AiModelCatalogRepository repository,
                                              AiProviderId providerId) {
        return repository.findEnabledProviderType(providerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "An enabled provider is required."));
    }

    private static AiModelProfile requireModelProfile(String providerType, String modelKey) {
        return AiModelProfileCatalog.find(providerType, modelKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The selected model is not supported by this provider."));
    }

    private static void validate(AiModelCatalogUpdate input, boolean update) {
        if (input == null || !StringUtils.hasText(input.modelKey())) {
            throw new IllegalArgumentException("A model is required.");
        }
        if (update && input.expectedVersion() == null) {
            throw new IllegalArgumentException("Expected catalog version is required.");
        }
        if (input.providerId() == null || input.providerId().value() == null
                || input.providerId().value().signum() <= 0 || input.sortOrder() < 0) {
            throw new IllegalArgumentException("Provider and sort order are invalid.");
        }
        if (input.defaultModel() && !input.enabled()) {
            throw new IllegalArgumentException("The global default model must be enabled.");
        }
    }
}
