package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Applies the configured retention window to complete AI conversation/audit data. */
@Component
public class AiChatRetentionService {

    private final ScoreChatMemoryRepository repository;
    private final ScoreAiProperties properties;
    private final Clock clock;

    @Autowired
    public AiChatRetentionService(ScoreChatMemoryRepository repository, ScoreAiProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    AiChatRetentionService(ScoreChatMemoryRepository repository, ScoreAiProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${score.ai.memory.cleanup-interval:24h}")
    public void deleteExpiredConversations() {
        if (properties.getMemory().getRetention().isNegative()
                || properties.getMemory().getRetention().isZero()) {
            return;
        }
        repository.deleteExpiredConversations(
                Instant.now(clock).minus(properties.getMemory().getRetention()));
    }

    /** Clears expired one-time grant digests close to their ten-minute validity window. */
    @Scheduled(fixedDelayString = "${score.ai.mutation-confirmation.cleanup-interval:10m}")
    public void expireMutationConfirmations() {
        repository.expireMutationConfirmations(Instant.now(clock));
    }
}
