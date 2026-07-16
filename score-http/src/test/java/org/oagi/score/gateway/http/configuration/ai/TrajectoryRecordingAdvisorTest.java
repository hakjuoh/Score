package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.service.AiTrajectoryRecorder;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrajectoryRecordingAdvisorTest {

    @Test
    void recordsOneAggregatedModelResponseWhilePreservingStreamingChunks() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatClientRequest request = mock(ChatClientRequest.class);
        Prompt prompt = mock(Prompt.class);
        when(request.prompt()).thenReturn(prompt);
        when(prompt.getInstructions()).thenReturn(List.of());
        when(request.context()).thenReturn(Map.of(AiTrajectoryRecorder.PHASE_CONTEXT_KEY, "assistant"));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk("Hel"), chunk("lo")));

        List<ChatClientResponse> chunks = new TrajectoryRecordingAdvisor(recorder)
                .adviseStream(request, chain).collectList().block();

        assertThat(chunks).hasSize(2);
        verify(recorder).recordToolResponses(List.of());
        verify(recorder).recordModelResponse(argThat(response -> response != null
                && "Hello".equals(response.getResult().getOutput().getText())),
                org.mockito.ArgumentMatchers.eq("assistant"));
    }

    private ChatClientResponse chunk(String content) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(
                        new Generation(new AssistantMessage(content)))))
                .build();
    }
}
