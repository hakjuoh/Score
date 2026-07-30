package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMaintenanceRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/** Applies the configured retention window to complete AI conversation/audit data. */
@Component
public class AiChatRetentionService {

    private final Function<ScoreUser, AiChatMaintenanceRepository> repositories;
    private final Supplier<ScoreUser> systemRequester;
    private final ScoreAiProperties properties;
    private final Clock clock;
    private final AiFileService files;

    @Autowired
    public AiChatRetentionService(RepositoryFactory repositoryFactory,
                                  SessionService sessionService,
                                  ScoreAiProperties properties,
                                  AiFileService files) {
        this(repositoryFactory::aiChatMaintenanceRepository,
                sessionService::getScoreSystemUser, properties, Clock.systemUTC(), files);
    }

    AiChatRetentionService(AiChatMaintenanceRepository repository, ScoreAiProperties properties,
                           Clock clock, AiFileService files) {
        this(ignored -> repository, () -> null, properties, clock, files);
    }

    private AiChatRetentionService(
            Function<ScoreUser, AiChatMaintenanceRepository> repositories,
            Supplier<ScoreUser> systemRequester,
            ScoreAiProperties properties, Clock clock, AiFileService files) {
        this.repositories = repositories;
        this.systemRequester = systemRequester;
        this.properties = properties;
        this.clock = clock;
        this.files = files;
    }

    @Scheduled(fixedDelayString = "${score.ai.memory.cleanup-interval:24h}")
    @Transactional
    public void deleteExpiredConversations() {
        if (properties.getMemory().getRetention().isNegative()
                || properties.getMemory().getRetention().isZero()) {
            return;
        }
        Instant cutoff = Instant.now(clock).minus(properties.getMemory().getRetention());
        AiChatMaintenanceRepository repository = repository();
        for (String conversationId : repository.findExpiredConversationGuids(cutoff)) {
            files.deleteConversationFilesForRetention(conversationId);
        }
        repository.deleteExpiredConversations(cutoff);
    }

    /** Clears expired one-time grant digests close to their ten-minute validity window. */
    @Scheduled(fixedDelayString = "${score.ai.change-confirmation.cleanup-interval:10m}")
    @Transactional
    public void expireChangeConfirmations() {
        repository().expireChangeConfirmations(Instant.now(clock));
    }

    private AiChatMaintenanceRepository repository() {
        return repositories.apply(systemRequester.get());
    }
}
