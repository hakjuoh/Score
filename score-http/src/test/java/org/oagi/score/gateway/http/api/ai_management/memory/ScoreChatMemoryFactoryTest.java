package org.oagi.score.gateway.http.api.ai_management.memory;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMemoryStorageRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreChatMemoryFactoryTest {

    @Test
    void createsANewRequesterScopedMemoryForEveryUse() {
        RepositoryFactory repositories = mock(RepositoryFactory.class);
        ScoreUser firstRequester = mock(ScoreUser.class);
        ScoreUser secondRequester = mock(ScoreUser.class);
        AiChatMemoryStorageRepository firstStorage = mock(AiChatMemoryStorageRepository.class);
        AiChatMemoryStorageRepository secondStorage = mock(AiChatMemoryStorageRepository.class);
        when(repositories.aiChatMemoryStorageRepository(
                firstRequester, AiChatJsonSerializer.getInstance())).thenReturn(firstStorage);
        when(repositories.aiChatMemoryStorageRepository(
                secondRequester, AiChatJsonSerializer.getInstance())).thenReturn(secondStorage);
        when(firstStorage.findByConversationId("first")).thenReturn(List.of());
        when(secondStorage.findByConversationId("second")).thenReturn(List.of());
        ScoreChatMemoryFactory factory = new ScoreChatMemoryFactory(
                repositories, new ScoreAiProperties(), TransactionOperations.withoutTransaction());

        var first = factory.create(firstRequester);
        var second = factory.create(secondRequester);

        assertThat(first).isNotSameAs(second);
        first.get("first");
        second.get("second");
        verify(repositories).aiChatMemoryStorageRepository(
                firstRequester, AiChatJsonSerializer.getInstance());
        verify(repositories).aiChatMemoryStorageRepository(
                secondRequester, AiChatJsonSerializer.getInstance());
        verify(firstStorage).findByConversationId("first");
        verify(secondStorage).findByConversationId("second");
    }
}
