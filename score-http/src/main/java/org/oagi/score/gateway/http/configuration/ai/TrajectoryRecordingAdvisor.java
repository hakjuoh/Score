package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/** Persists every provider response, including intermediate tool-call iterations. */
public final class TrajectoryRecordingAdvisor implements CallAdvisor, StreamAdvisor {

    private final AiTrajectoryRecorder recorder;

    public TrajectoryRecordingAdvisor(AiTrajectoryRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        Object phase = request.context().get(AiTrajectoryRecorder.PHASE_CONTEXT_KEY);
        recorder.recordToolResponses(request.prompt().getInstructions());
        recorder.recordModelResponse(response.chatResponse(), phase != null ? phase.toString() : null);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Object phase = request.context().get(AiTrajectoryRecorder.PHASE_CONTEXT_KEY);
        recorder.recordToolResponses(request.prompt().getInstructions());
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                chain.nextStream(request), response -> recorder.recordStreamingModelResponse(
                        response.chatResponse(), phase != null ? phase.toString() : null));
    }

    @Override
    public String getName() {
        return "connectCenter trajectory recorder";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 500;
    }
}
