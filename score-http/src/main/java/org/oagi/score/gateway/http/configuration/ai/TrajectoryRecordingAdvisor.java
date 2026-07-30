package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
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
    private final ScoreAiObservability observability;

    public TrajectoryRecordingAdvisor(AiTrajectoryRecorder recorder) {
        this(recorder, ScoreAiObservability.noop());
    }

    public TrajectoryRecordingAdvisor(AiTrajectoryRecorder recorder,
                                      ScoreAiObservability observability) {
        this.recorder = recorder;
        this.observability = observability != null ? observability : ScoreAiObservability.noop();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Object phase = request.context().get(AiTrajectoryRecorder.PHASE_CONTEXT_KEY);
        String phaseName = phase != null ? phase.toString() : null;
        AiTrajectoryRecorder.ModelCallRecording recording = recording(phaseName);
        ScoreAiObservability.ModelCall modelCall = modelCall(phaseName, recording);
        try {
            ChatClientResponse response = chain.nextCall(request);
            AiTrajectoryRecorder.ExecutionEventIdentity event =
                    recorder.recordModelResponse(recording, response.chatResponse(), false);
            modelCall.eventIdentity(event);
            modelCall.complete(response.chatResponse());
            recorder.recordToolResponses(request.prompt().getInstructions());
            return response;
        } catch (RuntimeException failure) {
            modelCall.eventIdentity(recorder.failModelCall(recording, failure));
            modelCall.fail(failure);
            throw failure;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Object phase = request.context().get(AiTrajectoryRecorder.PHASE_CONTEXT_KEY);
        String phaseName = phase != null ? phase.toString() : null;
        return Flux.defer(() -> {
            AiTrajectoryRecorder.ModelCallRecording recording = recording(phaseName);
            ScoreAiObservability.ModelCall modelCall = modelCall(phaseName, recording);
            modelCall.streaming();
            try {
                recorder.recordToolResponses(request.prompt().getInstructions());
                Flux<ChatClientResponse> source = chain.nextStream(request)
                        .doOnNext(response -> {
                            modelCall.firstChunk();
                            if (containsToken(response)) modelCall.firstToken();
                        });
                return new ChatClientMessageAggregator().aggregateChatClientResponse(
                                source, response -> {
                                    AiTrajectoryRecorder.ExecutionEventIdentity event =
                                            recorder.recordModelResponse(
                                            recording, response.chatResponse(), true);
                                    modelCall.eventIdentity(event);
                                    modelCall.complete(response.chatResponse());
                                })
                        .doOnError(failure -> {
                            modelCall.eventIdentity(recorder.failModelCall(recording, failure));
                            modelCall.fail(failure);
                        })
                        .doOnCancel(() -> {
                            modelCall.eventIdentity(recorder.failModelCall(recording,
                                    new java.util.concurrent.CancellationException(
                                            "Model stream cancelled")));
                            modelCall.cancel();
                        });
            } catch (RuntimeException failure) {
                modelCall.eventIdentity(recorder.failModelCall(recording, failure));
                modelCall.fail(failure);
                throw failure;
            }
        });
    }

    private ScoreAiObservability.ModelCall modelCall(
            String phase, AiTrajectoryRecorder.ModelCallRecording recording) {
        return observability.startModelCall(recorder.requestId(), recorder.modelName(),
                recorder.requestModelName(), recorder.modelProvider(), phase,
                recorder.activeAgentRunId(), recorder.conversationId(), recording.started());
    }

    private AiTrajectoryRecorder.ModelCallRecording recording(String phase) {
        AiTrajectoryRecorder.ModelCallRecording recording = recorder.beginModelCall(phase);
        return recording != null ? recording : AiTrajectoryRecorder.ModelCallRecording.noop();
    }

    private boolean containsToken(ChatClientResponse response) {
        return response != null && response.chatResponse() != null
                && response.chatResponse().getResults().stream().anyMatch(generation ->
                generation.getOutput() != null
                        && (org.springframework.util.StringUtils.hasText(
                        generation.getOutput().getText())
                        || !generation.getOutput().getToolCalls().isEmpty()));
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
