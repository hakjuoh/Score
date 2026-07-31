package org.oagi.score.gateway.http.configuration.ai;

import io.opentelemetry.api.metrics.LongCounter;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk;
import org.springframework.stereotype.Component;

/** Catalog refresh metrics are independent of the model registry they observe. */
@Component
final class AiCatalogObservability {

    private final LongCounter refreshes;

    AiCatalogObservability(ScoreAiObservabilitySdk sdk) {
        refreshes = sdk.openTelemetry().getMeter("org.oagi.score.ai")
                .counterBuilder("ai.catalog.cache_refresh")
                .setDescription("Runtime AI catalog cache refreshes")
                .setUnit("{event}").build();
    }

    void refreshed() {
        refreshes.add(1);
    }
}
