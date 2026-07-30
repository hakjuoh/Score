package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.artifact.AiArtifactService;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMaintenanceRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.security.SessionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
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
        AiArtifactService artifacts = mock(AiArtifactService.class);
        when(sessions.getScoreSystemUser()).thenReturn(systemRequester);
        when(repositories.aiChatMaintenanceRepository(systemRequester)).thenReturn(repository);
        AiChatRetentionService service = new AiChatRetentionService(
                repositories, sessions, new ScoreAiProperties(), artifacts);

        service.expireMutationConfirmations();

        verify(repositories).aiChatMaintenanceRepository(systemRequester);
    }

    @Test
    void expiresGrantsAndDeletesConversationsOutsideTheRetentionWindow() {
        AiChatMaintenanceRepository repository = mock(AiChatMaintenanceRepository.class);
        AiArtifactService artifacts = mock(AiArtifactService.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getMemory().setRetention(Duration.ofDays(90));
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        Instant cutoff = now.minus(Duration.ofDays(90));
        when(repository.findExpiredConversationGuids(cutoff))
                .thenReturn(List.of("conversation-1", "conversation-2"));
        AiChatRetentionService service = new AiChatRetentionService(
                repository, properties, Clock.fixed(now, ZoneOffset.UTC), artifacts);

        service.expireMutationConfirmations();
        service.deleteExpiredConversations();

        var ordered = inOrder(repository, artifacts);
        ordered.verify(repository).expireMutationConfirmations(now);
        ordered.verify(repository).findExpiredConversationGuids(cutoff);
        ordered.verify(artifacts).deleteConversationArtifactsForRetention("conversation-1");
        ordered.verify(artifacts).deleteConversationArtifactsForRetention("conversation-2");
        ordered.verify(repository).deleteExpiredConversations(cutoff);
    }

    @Test
    void disabledConversationRetentionDoesNotDisableSecurityGrantExpiry() {
        AiChatMaintenanceRepository repository = mock(AiChatMaintenanceRepository.class);
        AiArtifactService artifacts = mock(AiArtifactService.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getMemory().setRetention(Duration.ZERO);
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        AiChatRetentionService service = new AiChatRetentionService(
                repository, properties, Clock.fixed(now, ZoneOffset.UTC), artifacts);

        service.deleteExpiredConversations();
        service.expireMutationConfirmations();

        verify(repository).expireMutationConfirmations(now);
        verify(repository, never()).deleteExpiredConversations(now);
        verify(repository, never()).findExpiredConversationGuids(now);
    }

    @Test
    void providerCleanupFailureKeepsConversationMetadataForRetry() {
        AiChatMaintenanceRepository repository = mock(AiChatMaintenanceRepository.class);
        AiArtifactService artifacts = mock(AiArtifactService.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        Instant cutoff = now.minus(properties.getMemory().getRetention());
        when(repository.findExpiredConversationGuids(cutoff)).thenReturn(List.of("conversation-1"));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(artifacts).deleteConversationArtifactsForRetention("conversation-1");
        AiChatRetentionService service = new AiChatRetentionService(
                repository, properties, Clock.fixed(now, ZoneOffset.UTC), artifacts);

        assertThatThrownBy(service::deleteExpiredConversations)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage unavailable");

        verify(repository, never()).deleteExpiredConversations(cutoff);
    }
}
