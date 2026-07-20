package org.oagi.score.gateway.http.api.ai_management.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatMemoryEntry;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMemoryStorageRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreChatMemoryRepositoryTest {

    @Test
    void translatesStoredEntriesToSpringAiMessages() {
        AiChatMemoryStorageRepository storage = mock(AiChatMemoryStorageRepository.class);
        when(storage.findByConversationId("conversation-1")).thenReturn(List.of(
                new AiChatMemoryEntry("USER", "Hello", Map.of("source", "test"))));
        ScoreChatMemoryRepository repository = new ScoreChatMemoryRepository(storage);

        List<Message> messages = repository.findByConversationId("conversation-1");

        assertThat(messages).singleElement().isInstanceOf(UserMessage.class)
                .satisfies(message -> {
                    assertThat(message.getText()).isEqualTo("Hello");
                    assertThat(message.getMetadata()).containsEntry("source", "test");
                });
    }

    @Test
    void translatesSpringAiMessagesBeforeDelegatingPersistence() {
        AiChatMemoryStorageRepository storage = mock(AiChatMemoryStorageRepository.class);
        ScoreChatMemoryRepository repository = new ScoreChatMemoryRepository(storage);

        repository.saveAll("conversation-1", List.of(new AssistantMessage("Hello")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiChatMemoryEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(storage).saveAll(org.mockito.ArgumentMatchers.eq("conversation-1"), entries.capture());
        assertThat(entries.getValue()).singleElement().satisfies(entry -> {
            assertThat(entry.messageType()).isEqualTo("ASSISTANT");
            assertThat(entry.content()).isEqualTo("Hello");
        });
    }

    @Test
    void rejectsAnUnknownStoredMemoryTypeWithAClearError() {
        assertThatThrownBy(() -> ScoreChatMemoryRepository.storedMessageType("FUTURE_ROLE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message_type", "FUTURE_ROLE", "unsupported");
        assertThatThrownBy(() -> ScoreChatMemoryRepository.storedMessageType(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message_type", "missing", "no safe default");
    }
}
