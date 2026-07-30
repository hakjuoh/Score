package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiResponseContentTest {

    @Test
    void separatesVisibleTextFromAllSupportedReasoningMetadata() {
        AssistantMessage visibleStart = message("answer ", Map.of());
        AssistantMessage signature = message("private signature", Map.of("signature", "sig"));
        AssistantMessage data = message("private data", Map.of("data", "encrypted"));
        AssistantMessage thinking = message("private thought", Map.of("thinking", true));
        AssistantMessage visibleEnd = message("complete", Map.of("thinking", false));
        List<Generation> generations = List.of(
                new Generation(visibleStart), new Generation(signature), new Generation(data),
                new Generation(thinking), new Generation(visibleEnd));

        assertThat(SpringAiResponseContent.visibleStored(generations)).isEqualTo("answer complete");
        assertThat(SpringAiResponseContent.reasoning(generations))
                .isEqualTo("private signature\n\nprivate data\n\nprivate thought");
    }

    @Test
    void safelyHandlesMissingResponseAndEmptyContent() {
        assertThat(SpringAiResponseContent.visibleStreaming(null)).isEmpty();
        assertThat(SpringAiResponseContent.visibleStored(null)).isEmpty();
        assertThat(SpringAiResponseContent.reasoning(null)).isNull();
        assertThat(SpringAiResponseContent.isReasoning(null)).isFalse();
    }

    @Test
    void preservesWhitespaceChunksForStreamingButNotForStoredVisibleMessages() {
        List<Generation> generations = List.of(new Generation(message(" ", Map.of())));
        ChatResponse response = new ChatResponse(generations);

        assertThat(SpringAiResponseContent.visibleStreaming(response)).isEqualTo(" ");
        assertThat(SpringAiResponseContent.visibleStored(generations)).isEmpty();
    }

    @Test
    void streamingConcatenatesVisibleChunksAndExcludesReasoning() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(message("first", Map.of())),
                new Generation(message("private", Map.of("signature", "sig"))),
                new Generation(message(" second", Map.of()))));

        assertThat(SpringAiResponseContent.visibleStreaming(response)).isEqualTo("first second");
    }

    private static AssistantMessage message(String text, Map<String, Object> metadata) {
        AssistantMessage message = mock(AssistantMessage.class);
        when(message.getText()).thenReturn(text);
        when(message.getMetadata()).thenReturn(metadata);
        return message;
    }
}
