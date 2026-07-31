package org.oagi.score.gateway.http.api.ai_management.catalog.repository;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

/** Reads the enabled database catalog into the mutable runtime configuration graph. */
public interface AiDatabaseCatalogRepository {

    void loadInto(ScoreAiProperties properties);
}
