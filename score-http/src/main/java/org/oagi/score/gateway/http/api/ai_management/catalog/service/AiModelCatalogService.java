package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_CATALOG_CONFIG;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

/** Resolves the active database catalog against runtime-capable model adapters. */
@Service
public class AiModelCatalogService {

    private final DSLContext dsl;
    private final ScoreAiModelRegistry runtimeModels;

    public AiModelCatalogService(DSLContext dsl, ScoreAiModelRegistry runtimeModels) {
        this.dsl = dsl;
        this.runtimeModels = runtimeModels;
    }

    public List<AiCatalogModel> activeModels() {
        Map<String, ScoreAiModelRegistry.ModelDescriptor> runtime = new LinkedHashMap<>();
        runtimeModels.availableModels().forEach(model -> runtime.put(model.name(), model));
        if (runtime.isEmpty()) return List.of();

        var rows = dsl.select(AI_MODEL.AI_MODEL_ID, AI_MODEL.MODEL_KEY)
                .from(AI_MODEL)
                .join(AI_PROVIDER).on(AI_PROVIDER.AI_PROVIDER_ID.eq(AI_MODEL.PROVIDER_ID))
                .where(AI_MODEL.ENABLED.eq((byte) 1))
                .and(AI_PROVIDER.ENABLED.eq((byte) 1))
                .orderBy(AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID)
                .fetch();
        if (rows.isEmpty()) {
            return runtime.values().stream().map(model -> new AiCatalogModel(0L, model)).toList();
        }
        String defaultKey = dsl.select(AI_MODEL.MODEL_KEY)
                .from(AI_MODEL_CATALOG_CONFIG)
                .join(AI_MODEL).on(AI_MODEL.AI_MODEL_ID.eq(
                        AI_MODEL_CATALOG_CONFIG.DEFAULT_AI_MODEL_ID))
                .where(AI_MODEL_CATALOG_CONFIG.AI_MODEL_CATALOG_CONFIG_ID.eq(
                        org.jooq.types.UByte.valueOf(1)))
                .fetchOne(AI_MODEL.MODEL_KEY);
        return rows.stream().map(row -> {
                    String key = row.get(AI_MODEL.MODEL_KEY);
                    ScoreAiModelRegistry.ModelDescriptor descriptor = runtime.get(key);
                    if (descriptor == null) return null;
                    if (defaultKey != null && descriptor.defaultModel() != defaultKey.equals(key)) {
                        descriptor = new ScoreAiModelRegistry.ModelDescriptor(
                                descriptor.name(), descriptor.displayName(), descriptor.description(),
                                descriptor.provider(), defaultKey.equals(key),
                                descriptor.defaultReasoningEffort(), descriptor.reasoningEfforts(),
                                descriptor.contextBudget());
                    }
                    return new AiCatalogModel(row.get(AI_MODEL.AI_MODEL_ID).longValue(), descriptor);
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

    public Optional<AiCatalogModel> findById(long modelId) {
        return activeModels().stream().filter(model -> model.id() == modelId).findFirst();
    }

    public String defaultModelKey() {
        return activeModels().stream().filter(model -> model.descriptor().defaultModel())
                .map(model -> model.descriptor().name()).findFirst()
                .orElseGet(() -> activeModels().stream().findFirst()
                        .map(model -> model.descriptor().name()).orElse(null));
    }
}
