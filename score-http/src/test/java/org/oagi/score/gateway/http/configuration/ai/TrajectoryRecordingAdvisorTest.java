package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(recorder).recordStreamingModelResponse(argThat(response -> response != null
                && "Hello".equals(response.getResult().getOutput().getText())),
                org.mockito.ArgumentMatchers.eq("assistant"));
    }

    @Test
    void doesNotPublishIncompleteAnthropicPromptTokensAfterStreamAggregation() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(1L, 1L, Instant.now()));
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), mock(ScoreUser.class),
                "conversation-1", "request-1", "claude-fable-5", "high",
                ignored -> {}, null, 0L);
        recorder.useModelProvider("anthropic");
        ChatClientRequest request = mock(ChatClientRequest.class);
        Prompt prompt = mock(Prompt.class);
        when(request.prompt()).thenReturn(prompt);
        when(prompt.getInstructions()).thenReturn(List.of());
        when(request.context()).thenReturn(Map.of(
                AiTrajectoryRecorder.PHASE_CONTEXT_KEY, "assistant"));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(
                chunk("done", new DefaultUsage(2, 4, 6, null, null, null))));

        new TrajectoryRecordingAdvisor(recorder)
                .adviseStream(request, chain).collectList().block();

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().metrics())
                .doesNotContainKey("prompt_tokens")
                .containsEntry("provider_reported_prompt_tokens", 2L)
                .containsEntry("prompt_tokens_complete", false)
                .containsEntry("completion_tokens", 4)
                .containsEntry("prompt_token_accounting", "cache_excluded");
    }

    private ChatClientResponse chunk(String content) {
        return chunk(content, null);
    }

    private ChatClientResponse chunk(String content, DefaultUsage usage) {
        ChatResponse response = usage == null
                ? new ChatResponse(List.of(new Generation(new AssistantMessage(content))))
                : new ChatResponse(List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder().usage(usage).build());
        return ChatClientResponse.builder()
                .chatResponse(response)
                .build();
    }
}
