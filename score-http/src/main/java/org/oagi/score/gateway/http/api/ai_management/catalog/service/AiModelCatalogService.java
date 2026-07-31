package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiModelCatalogRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves the active database catalog against runtime-capable model adapters. */
@Service
public class AiModelCatalogService {

    private final RepositoryFactory repositoryFactory;
    private final ObjectMapper objectMapper;
    private final ScoreAiModelRegistry runtimeModels;

    public AiModelCatalogService(RepositoryFactory repositoryFactory, ObjectMapper objectMapper,
                                 ScoreAiModelRegistry runtimeModels) {
        this.repositoryFactory = repositoryFactory;
        this.objectMapper = objectMapper;
        this.runtimeModels = runtimeModels;
    }

    public List<AiCatalogModel> activeModels() {
        Map<String, ScoreAiModelRegistry.ModelDescriptor> runtime = new LinkedHashMap<>();
        runtimeModels.availableModels().forEach(model -> runtime.put(model.name(), model));
        if (runtime.isEmpty()) return List.of();

        AiModelCatalogRepository.ActiveCatalog catalog =
                repositoryFactory.aiModelCatalogRepository(objectMapper).findActiveCatalog();
        if (catalog.models().isEmpty()) {
            return runtime.values().stream()
                    .map(model -> new AiCatalogModel(new AiModelId(BigInteger.ZERO), model))
                    .toList();
        }
        String defaultKey = catalog.defaultModelKey();
        return catalog.models().stream().map(model -> {
                    ScoreAiModelRegistry.ModelDescriptor descriptor =
                            runtime.get(model.modelKey());
                    if (descriptor == null) return null;
                    if (defaultKey != null
                            && descriptor.defaultModel() != defaultKey.equals(model.modelKey())) {
                        descriptor = new ScoreAiModelRegistry.ModelDescriptor(
                                descriptor.name(), descriptor.displayName(),
                                descriptor.description(), descriptor.provider(),
                                defaultKey.equals(model.modelKey()),
                                descriptor.defaultReasoningEffort(),
                                descriptor.reasoningEfforts(), descriptor.contextBudget());
                    }
                    return new AiCatalogModel(model.id(), descriptor);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public Optional<AiCatalogModel> findByKey(String modelKey) {
        if (modelKey == null) return Optional.empty();
        return activeModels().stream()
                .filter(model -> model.descriptor().name().equals(modelKey.strip()))
                .findFirst();
    }

    public Optional<AiCatalogModel> findById(AiModelId modelId) {
        return activeModels().stream().filter(model -> model.id().equals(modelId)).findFirst();
    }

    public String defaultModelKey() {
        List<AiCatalogModel> activeModels = activeModels();
        return activeModels.stream().filter(model -> model.descriptor().defaultModel())
                .map(model -> model.descriptor().name()).findFirst()
                .orElseGet(() -> activeModels.stream().findFirst()
                        .map(model -> model.descriptor().name()).orElse(null));
    }
}
