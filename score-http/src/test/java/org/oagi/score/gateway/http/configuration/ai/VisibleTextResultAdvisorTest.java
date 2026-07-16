package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisibleTextResultAdvisorTest {

    @Test
    void promotesFinalTextAfterAdaptiveThinkingBlock() {
        Generation reasoning = new Generation(AssistantMessage.builder()
                .content("I should query the authoritative source.")
                .properties(Map.of("signature", "signed-thinking"))
                .build());
        Generation answer = new Generation(new AssistantMessage("There are 12 business contexts."));
        ChatClientResponse raw = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(reasoning, answer)))
                .build();
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(raw);

        ChatClientResponse normalized = new VisibleTextResultAdvisor().adviseCall(request, chain);

        assertThat(normalized.chatResponse().getResult().getOutput().getText())
                .isEqualTo("There are 12 business contexts.");
        assertThat(normalized.chatResponse().getResults()).containsExactly(answer, reasoning);
    }

    @Test
    void promotesVisibleTextInStreamingChunks() {
        Generation reasoning = new Generation(AssistantMessage.builder()
                .content("thinking")
                .properties(Map.of("thinking", true))
                .build());
        Generation answer = new Generation(new AssistantMessage("visible"));
        ChatClientResponse raw = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(reasoning, answer)))
                .build();
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(raw));

        ChatClientResponse normalized = new VisibleTextResultAdvisor()
                .adviseStream(request, chain).blockFirst();

        assertThat(normalized).isNotNull();
        assertThat(normalized.chatResponse().getResult().getOutput().getText()).isEqualTo("visible");
    }
}
