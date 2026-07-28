package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MiddlewareStatePropagationTest {

    @Test
    void preservesInvocationStateAcrossTheSpringAiTransportBoundary() {
        MiddlewareState state = new MiddlewareState();
        MiddlewareState.Key<AtomicInteger> key = new MiddlewareState.Key<>(
                "trace-policy", "calls", AtomicInteger.class);
        state.put(key, new AtomicInteger(1));
        AiMessage.User input = new AiMessage.User("input");
        ChatRequest request = new ChatRequest(input.content(), "request", "assistant",
                "conversation", null, List.of(), null, "model", null, null);
        ChatExecutionContext context = ChatExecutionContext.fromCoreMessages(
                        request, List.of(), input, null, mock(AiTrajectoryRecorder.class),
                        false, false, AgentToolPolicy.NONE, 0)
                .withAgentIdentity("assistant", ExecutionScope.Purpose.USER_RESPONSE);

        AiChatExecutor.Context provider = SpringAiExecutionContextMapper.toProvider(context, state);

        assertThat(provider.middlewareState()).isSameAs(state);
        assertThat(provider.middlewareState().get(key)).get()
                .extracting(AtomicInteger::get).isEqualTo(1);
    }
}
