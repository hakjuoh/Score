package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiCallReservation;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUsageSettlement;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiUsageAccountingService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.ProviderPromptTokenNormalizer;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reserves and settles exactly one ledger row for each provider-call advisor iteration. */
public final class ProviderCallAccountingAdvisor implements CallAdvisor, StreamAdvisor {
    private final AiUsageAccountingService accounting;
    private final ChatRequest transport;
    private final ExecutionScope scope;
    private final String agentId;
    private final String providerType;

    public ProviderCallAccountingAdvisor(AiUsageAccountingService accounting,
                                         ChatRequest transport, ExecutionScope scope,
                                         String agentId, String providerType) {
        this.accounting = Objects.requireNonNull(accounting, "accounting");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.agentId = agentId;
        this.providerType = providerType;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        AiCallReservation reservation = reserve(request);
        if (reservation == null) return chain.nextCall(request);
        ChatClientRequest limited;
        try {
            limited = applyOutputLimit(request, reservation);
        } catch (RuntimeException failure) {
            accounting.release(reservation, failure);
            throw failure;
        }
        try {
            ChatClientResponse response = chain.nextCall(limited);
            settle(reservation, response, false);
            return response;
        } catch (RuntimeException failure) {
            settleFailure(reservation, failure);
            throw failure;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                 StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            AiCallReservation reservation = reserve(request);
            if (reservation == null) return chain.nextStream(request);
            AtomicBoolean finished = new AtomicBoolean();
            ChatClientRequest limited;
            try {
                limited = applyOutputLimit(request, reservation);
            } catch (RuntimeException failure) {
                accounting.release(reservation, failure);
                throw failure;
            }
            try {
                Flux<ChatClientResponse> source = chain.nextStream(limited);
                return new ChatClientMessageAggregator().aggregateChatClientResponse(source,
                                response -> {
                                    if (finished.compareAndSet(false, true)) settle(reservation, response, true);
                                })
                        .doOnError(failure -> {
                            if (finished.compareAndSet(false, true)) settleFailure(reservation, failure);
                        })
                        .doOnCancel(() -> {
                            if (finished.compareAndSet(false, true)) {
                                settleFailure(reservation,
                                        new CancellationException("Model stream cancelled"));
                            }
                        });
            } catch (RuntimeException failure) {
                if (finished.compareAndSet(false, true)) settleFailure(reservation, failure);
                throw failure;
            }
        });
    }

    private AiCallReservation reserve(ChatClientRequest request) {
        return accounting.reserve(transport, scope, estimateTokens(request), agentId);
    }

    private long estimateTokens(ChatClientRequest request) {
        long bytes = 0L;
        for (var message : request.prompt().getInstructions()) {
            String text = message.getText();
            if (text != null) bytes = Math.addExact(bytes,
                    text.getBytes(StandardCharsets.UTF_8).length);
        }
        return Math.max(1L, (bytes + 2L) / 3L
                + 32L + request.prompt().getInstructions().size() * 8L);
    }

    private ChatClientRequest applyOutputLimit(ChatClientRequest request,
                                               AiCallReservation reservation) {
        if (reservation.effectiveMaxOutputTokens() == null) return request;
        int limit = reservation.effectiveMaxOutputTokens();
        ChatOptions options = request.prompt().getOptions();
        ChatOptions limited;
        if (options instanceof org.springframework.ai.anthropic.AnthropicChatOptions anthropic) {
            limited = anthropic.mutate().maxTokens(limit).build();
        } else if (options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi) {
            var builder = openAi.mutate();
            limited = (openAi.getMaxCompletionTokens() != null || openAi.getReasoningEffort() != null)
                    ? builder.maxCompletionTokens(limit).build()
                    : builder.maxTokens(limit).build();
        } else {
            limited = options.mutate().maxTokens(limit).build();
        }
        return request.mutate().prompt(new Prompt(request.prompt().getInstructions(), limited)).build();
    }

    private void settle(AiCallReservation reservation, ChatClientResponse response,
                        boolean streaming) {
        var usage = response != null && response.chatResponse() != null
                && response.chatResponse().getMetadata() != null
                ? response.chatResponse().getMetadata().getUsage() : null;
        var normalized = usage != null
                ? ProviderPromptTokenNormalizer.forProvider(providerType).normalize(usage, streaming)
                : null;
        long prompt = normalized != null ? Math.max(0L, normalized.inclusiveTokens()) : 0L;
        long completion = usage != null && usage.getCompletionTokens() != null
                ? Math.max(0L, usage.getCompletionTokens().longValue()) : 0L;
        long cached = usage != null && usage.getCacheReadInputTokens() != null
                ? Math.max(0L, usage.getCacheReadInputTokens().longValue()) : 0L;
        boolean complete = normalized != null && normalized.complete()
                && usage.getCompletionTokens() != null;
        accounting.settle(reservation, new AiUsageSettlement(prompt, completion, cached, complete));
    }

    private void settleFailure(AiCallReservation reservation, Throwable failure) {
        // Once the provider invocation has started, even a 4xx response can have
        // consumed provider-side tokens. Without authoritative usage, settle the
        // reservation conservatively and let estimation account for the call.
        accounting.settle(reservation, new AiUsageSettlement(0L, 0L, 0L, false));
    }

    @Override
    public String getName() {
        return "connectCenter provider-call quota accounting";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 450;
    }
}
