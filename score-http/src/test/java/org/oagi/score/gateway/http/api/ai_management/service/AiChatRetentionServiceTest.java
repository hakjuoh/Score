package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiChatRetentionServiceTest {

    @Test
    void expiresGrantsAndDeletesConversationsOutsideTheRetentionWindow() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
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
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
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
