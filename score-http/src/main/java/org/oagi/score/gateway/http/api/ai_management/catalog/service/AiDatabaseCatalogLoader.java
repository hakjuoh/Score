package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

/** Replaces legacy YAML catalog values with the enabled database catalog at startup. */
public final class AiDatabaseCatalogLoader {

    private final RepositoryFactory repositoryFactory;
    private final ApplicationSecretService secrets;
    private final ObjectMapper objectMapper;

    public AiDatabaseCatalogLoader(RepositoryFactory repositoryFactory,
                                   ApplicationSecretService secrets,
                                   ObjectMapper objectMapper) {
        this.repositoryFactory = repositoryFactory;
        this.secrets = secrets;
        this.objectMapper = objectMapper;
    }

    public void loadInto(ScoreAiProperties properties) {
        repositoryFactory.aiDatabaseCatalogRepository(secrets, objectMapper)
                .loadInto(properties);
    }

}
