package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiCallReservation;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUsageSettlement;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiUsageAccountingService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ProviderCallAccountingAdvisorTest {

    @Test
    void createsAndSettlesOneReservationForEveryToolLoopIteration() {
        AiUsageAccountingService accounting = mock(AiUsageAccountingService.class);
        ChatRequest transport = new ChatRequest("question", "request-1", null,
                "conversation-1", null, List.of(), null, "model-1", "high", null);
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "1",
                1L, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiCallReservation first = reservation("request-1");
        AiCallReservation second = reservation("request-1");
        when(accounting.reserve(eq(transport), eq(scope), any(Long.class), eq(null)))
                .thenReturn(first, second);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request();
        when(chain.nextCall(request)).thenReturn(response(12, 4));
        ProviderCallAccountingAdvisor advisor = new ProviderCallAccountingAdvisor(
                accounting, transport, scope, null, "openai");

        advisor.adviseCall(request, chain);
        advisor.adviseCall(request, chain);

        verify(accounting, times(2)).reserve(eq(transport), eq(scope), any(Long.class), eq(null));
        verify(accounting).settle(eq(first), eq(new AiUsageSettlement(12, 4, 0, true)));
        verify(accounting).settle(eq(second), eq(new AiUsageSettlement(12, 4, 0, true)));
    }

    @Test
    void doesNotApplyAnOutputOptionWhenReservationHasNoEffectiveCap() {
        AiUsageAccountingService accounting = mock(AiUsageAccountingService.class);
        ChatRequest transport = new ChatRequest("question", "request-2", null,
                "conversation-2", null, List.of(), null, "model-1", "high", null);
        ExecutionScope scope = new ExecutionScope("request-2", "conversation-2", "1",
                1L, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        when(accounting.reserve(eq(transport), eq(scope), any(Long.class), eq(null)))
                .thenReturn(reservation("request-2"));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request();
        when(chain.nextCall(request)).thenReturn(response(2, 1));

        new ProviderCallAccountingAdvisor(accounting, transport, scope, null, "openai")
                .adviseCall(request, chain);

        verify(chain).nextCall(request);
    }

    @Test
    void conservativelySettlesAnthropicStreamWhenCacheUsageIsMissing() {
        AiUsageAccountingService accounting = mock(AiUsageAccountingService.class);
        ChatRequest transport = new ChatRequest("question", "request-stream", null,
                "conversation-stream", null, List.of(), null, "model-1", "high", null);
        ExecutionScope scope = new ExecutionScope("request-stream", "conversation-stream", "1",
                1L, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiCallReservation reservation = reservation("request-stream");
        when(accounting.reserve(eq(transport), eq(scope), any(Long.class), eq(null)))
                .thenReturn(reservation);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = request();
        when(chain.nextStream(request)).thenReturn(Flux.just(response(12, 4)));

        new ProviderCallAccountingAdvisor(accounting, transport, scope, null, "anthropic")
                .adviseStream(request, chain).collectList().block();

        verify(accounting).settle(eq(reservation), eq(new AiUsageSettlement(12, 4, 0, false)));
    }

    @Test
    void estimatesTransientProviderFailuresAfterAnAttemptStarts() {
        AiUsageAccountingService accounting = mock(AiUsageAccountingService.class);
        ChatRequest transport = new ChatRequest("question", "request-failure", null,
                "conversation-failure", null, List.of(), null, "model-1", "high", null);
        ExecutionScope scope = new ExecutionScope("request-failure", "conversation-failure", "1",
                1L, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiCallReservation reservation = reservation("request-failure");
        when(accounting.reserve(eq(transport), eq(scope), any(Long.class), eq(null)))
                .thenReturn(reservation);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request();
        var failure = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "rate limited",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        when(chain.nextCall(request)).thenThrow(failure);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ProviderCallAccountingAdvisor(accounting, transport, scope, null, "openai")
                        .adviseCall(request, chain)).isSameAs(failure);

        verify(accounting).settle(eq(reservation), eq(new AiUsageSettlement(0, 0, 0, false)));
        verify(accounting, never()).release(eq(reservation), any());
    }

    @Test
    void estimatesNonRetryableProviderFailuresAfterAnAttemptStarts() {
        AiUsageAccountingService accounting = mock(AiUsageAccountingService.class);
        ChatRequest transport = new ChatRequest("question", "request-bad", null,
                "conversation-bad", null, List.of(), null, "model-1", "high", null);
        ExecutionScope scope = new ExecutionScope("request-bad", "conversation-bad", "1",
                1L, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiCallReservation reservation = reservation("request-bad");
        when(accounting.reserve(eq(transport), eq(scope), any(Long.class), eq(null)))
                .thenReturn(reservation);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request();
        var failure = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_REQUEST, "bad request",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        when(chain.nextCall(request)).thenThrow(failure);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ProviderCallAccountingAdvisor(accounting, transport, scope, null, "openai")
                        .adviseCall(request, chain)).isSameAs(failure);

        verify(accounting).settle(eq(reservation), eq(new AiUsageSettlement(0, 0, 0, false)));
        verify(accounting, never()).release(eq(reservation), any());
    }

    private AiCallReservation reservation(String requestId) {
        return new AiCallReservation(UUID.randomUUID(), requestId,
                new UserId(BigInteger.ONE), 100, null, null);
    }

    private ChatClientRequest request() {
        return ChatClientRequest.builder().prompt(new Prompt(
                List.of(new UserMessage("hello")), ChatOptions.builder().build())).build();
    }

    private ChatClientResponse response(long prompt, long completion) {
        return ChatClientResponse.builder().chatResponse(new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(Math.toIntExact(prompt),
                                Math.toIntExact(completion), Math.toIntExact(prompt + completion)))
                        .build())).build();
    }
}
