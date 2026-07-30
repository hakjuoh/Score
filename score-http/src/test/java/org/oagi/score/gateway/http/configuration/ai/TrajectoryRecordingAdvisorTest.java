package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        AiTrajectoryRecorder.ModelCallRecording recording =
                AiTrajectoryRecorder.ModelCallRecording.noop();
        when(recorder.beginModelCall("assistant")).thenReturn(recording);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk("Hel"), chunk("lo")));

        List<ChatClientResponse> chunks = new TrajectoryRecordingAdvisor(recorder)
                .adviseStream(request, chain).collectList().block();

        assertThat(chunks).hasSize(2);
        verify(recorder).recordToolResponses(List.of());
        verify(recorder).recordModelResponse(eq(recording), argThat(response -> response != null
                && "Hello".equals(response.getResult().getOutput().getText())), eq(true));
    }

    @Test
    void recordsFirstChunkSeparatelyFromFirstContentToken() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.requestId()).thenReturn("request-stream");
        when(recorder.modelName()).thenReturn("claude-fable-5_alias");
        when(recorder.requestModelName()).thenReturn("claude-fable-5");
        when(recorder.modelProvider()).thenReturn("anthropic");
        AiTrajectoryRecorder.ModelCallRecording recording =
                AiTrajectoryRecorder.ModelCallRecording.noop();
        when(recorder.beginModelCall("assistant")).thenReturn(recording);
        ChatClientRequest request = request("assistant");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(chunk(""), chunk("done")));
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.ModelCall modelCall = mock(ScoreAiObservability.ModelCall.class);
        when(observability.startModelCall(
                "request-stream", "claude-fable-5_alias", "claude-fable-5",
                "anthropic", "assistant", null, null, null))
                .thenReturn(modelCall);

        new TrajectoryRecordingAdvisor(recorder, observability)
                .adviseStream(request, chain).collectList().block();

        verify(modelCall).streaming();
        verify(modelCall, times(2)).firstChunk();
        verify(modelCall).firstToken();
    }

    @Test
    void marksStreamingWhenProviderFailsBeforeFirstChunk() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.requestId()).thenReturn("request-failure");
        when(recorder.modelName()).thenReturn("claude-fable-5_alias");
        when(recorder.requestModelName()).thenReturn("claude-fable-5");
        when(recorder.modelProvider()).thenReturn("anthropic");
        AiTrajectoryRecorder.ModelCallRecording recording =
                AiTrajectoryRecorder.ModelCallRecording.noop();
        when(recorder.beginModelCall("assistant")).thenReturn(recording);
        ChatClientRequest request = request("assistant");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        IllegalStateException failure = new IllegalStateException("provider unavailable");
        when(chain.nextStream(request)).thenReturn(Flux.error(failure));
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.ModelCall modelCall = mock(ScoreAiObservability.ModelCall.class);
        when(observability.startModelCall(
                "request-failure", "claude-fable-5_alias", "claude-fable-5",
                "anthropic", "assistant", null, null, null))
                .thenReturn(modelCall);

        assertThatThrownBy(() -> new TrajectoryRecordingAdvisor(recorder, observability)
                .adviseStream(request, chain).collectList().block())
                .isInstanceOf(IllegalStateException.class);

        verify(modelCall).streaming();
        verify(modelCall, never()).firstChunk();
        verify(modelCall, never()).firstToken();
        verify(modelCall).fail(failure);
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
        verify(repository).updateModelCall(eq("conversation-1"), eq(1L), step.capture());
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

    private ChatClientRequest request(String phase) {
        ChatClientRequest request = mock(ChatClientRequest.class);
        Prompt prompt = mock(Prompt.class);
        when(request.prompt()).thenReturn(prompt);
        when(prompt.getInstructions()).thenReturn(List.of());
        when(request.context()).thenReturn(Map.of(
                AiTrajectoryRecorder.PHASE_CONTEXT_KEY, phase));
        return request;
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
