package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiDatabaseCatalogLoader;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.springframework.stereotype.Component;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL_CATALOG_CONFIG;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

/** Detects catalog versions in the shared DB and atomically refreshes runtime clients. */
@Component
public class AiDynamicModelCatalog {
    private final DSLContext dsl;
    private final ScoreAiProperties properties;
    private final AnthropicChatProperties anthropic;
    private final OpenAiChatProperties openAi;
    private final ScoreAiConfiguration configuration;
    private final AiDatabaseCatalogLoader loader;
    private final AiCatalogObservability observability;
    private volatile String installedFingerprint;

    public AiDynamicModelCatalog(DSLContext dsl, ScoreAiProperties properties,
                                 AnthropicChatProperties anthropic,
                                 OpenAiChatProperties openAi,
                                 ScoreAiConfiguration configuration,
                                 ApplicationSecretService secrets,
                                 ObjectMapper objectMapper,
                                 AiCatalogObservability observability) {
        this.dsl = dsl;
        this.properties = properties;
        this.anthropic = anthropic;
        this.openAi = openAi;
        this.configuration = configuration;
        this.loader = new AiDatabaseCatalogLoader(dsl, secrets, objectMapper);
        this.observability = observability;
    }

    public void refreshIfChanged(ScoreAiModelRegistry registry) {
        String observed = fingerprint();
        if (observed.equals(installedFingerprint)) return;
        synchronized (this) {
            observed = fingerprint();
            if (observed.equals(installedFingerprint)) return;
            ScoreAiProperties candidate = new ScoreAiProperties();
            loader.loadInto(candidate);
            try {
                var clients = configuration.createChatModelsFromProperties(
                        candidate, anthropic, openAi);
                registry.install(clients, candidate.getProviders(), candidate.getModels(),
                        candidate.getModelName());
            } finally {
                configuration.clearProviderKeys(candidate);
            }
            installedFingerprint = observed;
            observability.refreshed();
        }
    }

    private String fingerprint() {
        var providers = dsl.select(org.jooq.impl.DSL.count(),
                        org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(
                                AI_PROVIDER.CATALOG_VERSION), java.math.BigDecimal.ZERO))
                .from(AI_PROVIDER).fetchOne();
        var models = dsl.select(org.jooq.impl.DSL.count(),
                        org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(
                                AI_MODEL.CATALOG_VERSION), java.math.BigDecimal.ZERO))
                .from(AI_MODEL).fetchOne();
        Object changed = dsl.select(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_AT)
                .from(AI_MODEL_CATALOG_CONFIG).fetchOne(AI_MODEL_CATALOG_CONFIG.LAST_UPDATED_AT);
        return providers.value1() + ":" + providers.value2() + ":"
                + models.value1() + ":" + models.value2() + ":" + changed;
    }
}
