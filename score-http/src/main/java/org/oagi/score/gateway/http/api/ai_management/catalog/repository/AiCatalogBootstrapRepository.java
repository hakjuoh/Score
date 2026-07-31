package org.oagi.score.gateway.http.api.ai_management.catalog.repository;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

/** Persists the legacy property-based AI catalog when no database catalog exists yet. */
public interface AiCatalogBootstrapRepository {

    void bootstrap(ScoreAiProperties properties);
}
