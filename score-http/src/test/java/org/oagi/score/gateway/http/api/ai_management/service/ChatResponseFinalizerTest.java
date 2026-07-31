package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentIdentityProvider;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatResponseFinalizerTest {

    @Test
    void checksCancellationBeforeDisclosureOrContentPublication() {
        ChatOutputDiscloser discloser = mock(ChatOutputDiscloser.class);
        ChatResultCommitter results = mock(ChatResultCommitter.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatRequest request = request();
        doThrow(new CancellationException("discarded"))
                .when(results).rejectDiscarded("request-1");
        ChatResponseFinalizer finalizer = new ChatResponseFinalizer(
                identity(), discloser, results, null);

        assertThatThrownBy(() -> finalizer.finalizeOutput(
                ChatTurnOutput.disclosable(new AgentOutput("candidate")), request,
                List.of(), new UserMessage("prompt"), mock(ScoreUser.class), recorder,
                mock(ExecutionScope.class)))
                .isInstanceOf(CancellationException.class);

        verify(discloser, never()).disclose(any(), any(), any(), any(), any(), any(), any());
        verify(recorder, never()).contentDelta(any());
    }

    @Test
    void publishesOnlyTheDisclosedAnswerAndItsCommittedTrace() {
        ChatOutputDiscloser discloser = mock(ChatOutputDiscloser.class);
        ChatResultCommitter results = mock(ChatResultCommitter.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatRequest request = request();
        ChatDisclosure disclosure = new ChatDisclosure(
                "safe answer", Map.of("agentId", "specialist"),
                mock(PublicOutputDisclosureGate.Outcome.class));
        when(discloser.disclose(any(), eq(request), any(), any(), any(), eq(recorder), any()))
                .thenReturn(disclosure);
        when(discloser.committedTrace(disclosure, "specialist"))
                .thenReturn(Map.of("agent_id", "specialist"));
        ChatResponseFinalizer finalizer = new ChatResponseFinalizer(
                identity(), discloser, results, null);

        ChatResponseFinalizer.FinalizedOutput output = finalizer.finalizeOutput(
                ChatTurnOutput.disclosable(new AgentOutput("candidate")), request,
                List.of(), new UserMessage("prompt"), mock(ScoreUser.class), recorder,
                mock(ExecutionScope.class));

        assertThat(output.agentId()).isEqualTo("specialist");
        assertThat(output.answer()).isEqualTo("safe answer");
        assertThat(output.committedTrace()).containsEntry("agent_id", "specialist");
        verify(recorder).contentDelta("safe answer");
    }

    private static AgentIdentityProvider identity() {
        return () -> "root";
    }

    private static ChatRequest request() {
        return new ChatRequest("prompt", "request-1", null, "conversation-1",
                null, List.of(), null, "model", "medium", "ask");
    }
}
