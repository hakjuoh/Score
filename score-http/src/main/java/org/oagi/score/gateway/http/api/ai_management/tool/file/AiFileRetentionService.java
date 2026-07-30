package org.oagi.score.gateway.http.api.ai_management.tool.file;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AiFileRetentionService {

    private final AiFileService files;

    public AiFileRetentionService(AiFileService files) { this.files = files; }

    @Scheduled(fixedDelayString = "${score.ai.tools.files.cleanup-interval:24h}")
    public void purgeExpiredFiles() {
        files.purgeExpired(Instant.now());
    }
}
