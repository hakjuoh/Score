package org.oagi.score.gateway.http.api.ai_management.artifact;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AiArtifactRetentionService {

    private final AiArtifactService artifacts;

    public AiArtifactRetentionService(AiArtifactService artifacts) { this.artifacts = artifacts; }

    @Scheduled(fixedDelayString = "${score.ai.tools.artifacts.cleanup-interval:24h}")
    public void purgeExpiredArtifacts() {
        artifacts.purgeExpired(Instant.now());
    }
}
