package org.oagi.score.gateway.http.security.secret;

import io.opentelemetry.api.metrics.LongCounter;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk;
import org.springframework.stereotype.Component;

/** Secret metrics stay independent of model-registry creation to avoid a startup cycle. */
@Component
final class AiSecretObservability {

    private final LongCounter decryptionFailures;

    AiSecretObservability(ScoreAiObservabilitySdk sdk) {
        decryptionFailures = sdk.openTelemetry().getMeter("org.oagi.score.ai")
                .counterBuilder("ai.provider.secret_decryption_failed")
                .setDescription("AI provider secret decryption failures")
                .setUnit("{event}").build();
    }

    void decryptionFailed() {
        decryptionFailures.add(1);
    }
}
