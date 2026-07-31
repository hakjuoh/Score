package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Imports the resolved legacy catalog once, and only when every catalog table is empty. */
@Component
@ConditionalOnProperty(prefix = "score.ai.catalog", name = "bootstrap-enabled", havingValue = "true")
@DependsOnDatabaseInitialization
public class AiCatalogBootstrap implements ApplicationRunner {

    private final RepositoryFactory repositoryFactory;
    private final ScoreAiProperties properties;
    private final ApplicationSecretService secrets;

    public AiCatalogBootstrap(RepositoryFactory repositoryFactory, ScoreAiProperties properties,
                              ApplicationSecretService secrets) {
        this.repositoryFactory = repositoryFactory;
        this.properties = properties;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapNow();
    }

    public void bootstrapNow() {
        AiModelProfileCatalog.install(properties);
        if (properties.getProviders().isEmpty() || properties.getModels().isEmpty()) return;
        if (!secrets.isEncryptionConfigured() && properties.getProviders().values().stream()
                .anyMatch(provider -> StringUtils.hasText(provider.getKey()))) {
            return;
        }
        repositoryFactory.aiCatalogBootstrapRepository(secrets).bootstrap(properties);
    }
}
