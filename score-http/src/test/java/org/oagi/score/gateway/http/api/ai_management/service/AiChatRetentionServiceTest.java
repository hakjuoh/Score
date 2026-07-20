package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMaintenanceRepository;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.security.SessionService;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatRetentionServiceTest {

    @Test
    void createsMaintenanceRepositoriesWithTheSystemRequester() {
        RepositoryFactory repositories = mock(RepositoryFactory.class);
        SessionService sessions = mock(SessionService.class);
        ScoreUser systemRequester = mock(ScoreUser.class);
        AiChatMaintenanceRepository repository = mock(AiChatMaintenanceRepository.class);
        when(sessions.getScoreSystemUser()).thenReturn(systemRequester);
        when(repositories.aiChatMaintenanceRepository(systemRequester)).thenReturn(repository);
        AiChatRetentionService service = new AiChatRetentionService(
                repositories, sessions, new ScoreAiProperties());

        service.expireMutationConfirmations();

        verify(repositories).aiChatMaintenanceRepository(systemRequester);
    }

    @Test
    void expiresGrantsAndDeletesConversationsOutsideTheRetentionWindow() {
        AiChatMaintenanceRepository repository = mock(AiChatMaintenanceRepository.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getMemory().setRetention(Duration.ofDays(90));
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        AiChatRetentionService service = new AiChatRetentionService(
                repository, properties, Clock.fixed(now, ZoneOffset.UTC));

        service.expireMutationConfirmations();
        service.deleteExpiredConversations();

        var ordered = inOrder(repository);
        ordered.verify(repository).expireMutationConfirmations(now);
        ordered.verify(repository).deleteExpiredConversations(now.minus(Duration.ofDays(90)));
    }

    @Test
    void disabledConversationRetentionDoesNotDisableSecurityGrantExpiry() {
        AiChatMaintenanceRepository repository = mock(AiChatMaintenanceRepository.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getMemory().setRetention(Duration.ZERO);
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        AiChatRetentionService service = new AiChatRetentionService(
                repository, properties, Clock.fixed(now, ZoneOffset.UTC));

        service.deleteExpiredConversations();
        service.expireMutationConfirmations();

        verify(repository).expireMutationConfirmations(now);
        verify(repository, never()).deleteExpiredConversations(now);
    }
}
