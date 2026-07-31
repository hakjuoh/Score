package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns one provider inference span, token accounting, cost, and terminal outcome. */
final class AiModelCallTelemetry {

    private final AiTurnState turn;
    private final Span span;
    private final String model;
    private final String provider;
    private final AiTurnState.AgentInvocation agentInvocation;
    private final long sequence;
    private final long startedNanos;
    private final boolean recording;
    private final AiObservationInstruments instruments;
    private final Consumer<BigDecimal> costRecorder;
    private final AtomicBoolean firstChunk = new AtomicBoolean();
    private final AtomicBoolean firstToken = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private String finishReason = "unknown";
    private String responseModel;

    AiModelCallTelemetry(AiTurnState turn, Span span, String model, String provider,
                         AiTurnState.AgentInvocation agentInvocation, long sequence,
                         long startedNanos, boolean recording,
                         AiObservationInstruments instruments,
                         Consumer<BigDecimal> costRecorder) {
        this.turn = turn;
        this.span = span;
        this.model = model;
        this.provider = provider;
        this.agentInvocation = agentInvocation;
        this.sequence = sequence;
        this.startedNanos = startedNanos;
        this.recording = recording;
        this.instruments = instruments;
        this.costRecorder = costRecorder;
    }

    void streaming() {
        if (recording && !turn.ended.get() && !ended.get()) {
            span.setAttribute("gen_ai.request.stream", true);
        }
    }

    void firstChunk() {
        if (!recording) return;
        synchronized (turn) {
            if (turn.ended.get() || ended.get() || !firstChunk.compareAndSet(false, true)) return;
            double duration = AiObservationTiming.elapsedMillis(startedNanos);
            span.setAttribute("gen_ai.response.time_to_first_chunk", duration / 1_000.0);
            instruments.genAiClientTimeToFirstChunk.record(
                    AiObservationTiming.elapsedSeconds(startedNanos),
                    GenAiSemanticConventions.inferenceAttributes(
                            GenAiSemanticConventions.CHAT, provider, model, responseModel, null));
        }
    }

    void firstToken() {
        if (!recording) return;
        synchronized (turn) {
            if (turn.ended.get() || ended.get() || !firstToken.compareAndSet(false, true)) return;
            double duration = AiObservationTiming.elapsedMillis(startedNanos);
            span.setAttribute("score.ai.time_to_first_token_ms", duration);
            if (turn.firstToken.compareAndSet(false, true)) {
                double turnTtft = AiObservationTiming.elapsedMillis(turn.startedNanos);
                turn.span.setAttribute("score.ai.time_to_first_token_ms", turnTtft);
                instruments.timeToFirstToken.record(turnTtft,
                        AiObservationLabels.providerModel(provider, model, null));
            }
        }
    }

    void eventIdentity(AiTrajectoryRecorder.ExecutionEventIdentity event) {
        if (!recording || event == null || turn.ended.get() || ended.get()) return;
        span.setAttribute("score.event.end.id", event.eventId());
        span.setAttribute("score.event.end.sequence", event.sequence());
        span.setAttribute("score.event.end.occurred_at", event.occurredAt().toString());
    }

    void complete(ChatResponse response) {
        if (!recording) return;
        synchronized (turn) {
            if (!ended.compareAndSet(false, true)) return;
            RuntimeException observationFailure = null;
            try {
                if (response != null) recordResponse(response);
            } catch (RuntimeException failure) {
                observationFailure = failure;
            }
            finishLocked(observationFailure == null ? "success" : "error",
                    observationFailure, false);
        }
    }

    void fail(Throwable failure) {
        if (!recording) return;
        synchronized (turn) {
            if (ended.compareAndSet(false, true)) finishLocked("error", failure, false);
        }
    }

    void cancel() {
        if (!recording) return;
        synchronized (turn) {
            if (ended.compareAndSet(false, true)) finishLocked("cancelled", null, false);
        }
    }

    void closeFromTurn(String turnOutcome) {
        synchronized (turn) {
            if (!ended.compareAndSet(false, true)) return;
            String outcome = switch (turnOutcome) {
                case "cancelled", "timeout" -> turnOutcome;
                default -> "error";
            };
            finishLocked(outcome, null, true);
        }
    }

    private void recordResponse(ChatResponse response) {
        if (StringUtils.hasText(response.getMetadata().getId())) {
            span.setAttribute("gen_ai.response.id", response.getMetadata().getId());
        }
        if (StringUtils.hasText(response.getMetadata().getModel())) {
            responseModel = response.getMetadata().getModel().strip();
            span.setAttribute("gen_ai.response.model", responseModel);
        }
        List<String> finishReasons = response.getResults().stream()
                .map(generation -> generation.getMetadata().getFinishReason())
                .filter(StringUtils::hasText)
                .map(AiObservationLabels::finishReasonValue)
                .toList();
        if (!finishReasons.isEmpty()) {
            finishReason = AiObservationLabels.finishReasonCategory(finishReasons.getFirst());
            span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                    finishReasons);
            if (agentInvocation != null) {
                agentInvocation.recordFinishReasons(sequence, finishReasons);
            } else {
                turn.recordFinishReasons(sequence, finishReasons);
            }
        }
        recordTokens(response.getMetadata().getUsage());
        recordProviderCost(response);
    }

    private void recordTokens(Usage usage) {
        if (usage == null) return;
        Number inputTokens = usage.getPromptTokens();
        if ("anthropic".equals(provider)) {
            long total = tokenTotal(usage.getPromptTokens(), usage.getCacheReadInputTokens(),
                    usage.getCacheWriteInputTokens());
            inputTokens = total >= 0 ? total : null;
        }
        standardToken("input", inputTokens, "gen_ai.usage.input_tokens");
        standardToken("output", usage.getCompletionTokens(), "gen_ai.usage.output_tokens");
        cacheToken("cache_read", usage.getCacheReadInputTokens(),
                "gen_ai.usage.cache_read.input_tokens");
        cacheToken("cache_write", usage.getCacheWriteInputTokens(),
                "gen_ai.usage.cache_creation.input_tokens");
    }

    private long tokenTotal(Number... counts) {
        long total = 0;
        boolean found = false;
        for (Number count : counts) {
            if (count == null || count.longValue() < 0) continue;
            found = true;
            long value = count.longValue();
            total = Long.MAX_VALUE - total < value ? Long.MAX_VALUE : total + value;
        }
        return found ? total : -1;
    }

    private void standardToken(String type, Number count, String spanAttribute) {
        if (count == null || count.longValue() < 0) return;
        instruments.tokens.add(count.longValue(), Attributes.builder()
                .putAll(AiObservationLabels.providerModel(provider, model, null))
                .put("score.ai.token.type", type).build());
        Attributes standard = Attributes.builder()
                .putAll(GenAiSemanticConventions.inferenceAttributes(
                        GenAiSemanticConventions.CHAT, provider, model, responseModel, null))
                .put("gen_ai.token.type", type).build();
        instruments.genAiClientTokenUsage.record(count.longValue(), standard);
        span.setAttribute(spanAttribute, count.longValue());
        AtomicLong aggregate = "input".equals(type)
                ? agentInvocation != null ? agentInvocation.inputTokens : turn.rootInputTokens
                : agentInvocation != null ? agentInvocation.outputTokens : turn.rootOutputTokens;
        aggregate.addAndGet(count.longValue());
    }

    private void cacheToken(String type, Number count, String spanAttribute) {
        if (count == null || count.longValue() < 0) return;
        instruments.tokens.add(count.longValue(), Attributes.builder()
                .putAll(AiObservationLabels.providerModel(provider, model, null))
                .put("score.ai.token.type", type).build());
        span.setAttribute(spanAttribute, count.longValue());
        boolean cacheRead = "cache_read".equals(type);
        AtomicLong aggregate = agentInvocation != null
                ? (cacheRead ? agentInvocation.cacheReadTokens : agentInvocation.cacheCreationTokens)
                : (cacheRead ? turn.rootCacheReadTokens : turn.rootCacheCreationTokens);
        AtomicBoolean observed = agentInvocation != null
                ? (cacheRead ? agentInvocation.cacheReadObserved
                        : agentInvocation.cacheCreationObserved)
                : (cacheRead ? turn.rootCacheReadObserved : turn.rootCacheCreationObserved);
        aggregate.addAndGet(count.longValue());
        observed.set(true);
    }

    private void recordProviderCost(ChatResponse response) {
        Object reported = response.getMetadata().get("cost_usd");
        if (reported == null) reported = response.getMetadata().get("costUsd");
        if (reported == null && response.getMetadata().getUsage() != null
                && response.getMetadata().getUsage().getNativeUsage()
                instanceof Map<?, ?> nativeUsage) {
            reported = nativeUsage.get("cost_usd");
            if (reported == null) reported = nativeUsage.get("costUsd");
        }
        BigDecimal cost = AiObservationLabels.nonNegativeDecimal(reported);
        if (cost == null) return;
        costRecorder.accept(cost);
        span.setAttribute("score.ai.cost_usd", cost.doubleValue());
    }

    private void finishLocked(String outcome, Throwable failure, boolean incomplete) {
        if (turn != null) turn.activeModelCalls.remove(this);
        if (failure != null) {
            span.setAttribute("error.type", failure.getClass().getName());
            span.setStatus(StatusCode.ERROR, outcome);
        } else if (!"success".equals(outcome)) {
            span.setAttribute("error.type", outcome);
            span.setStatus(StatusCode.ERROR, outcome);
        }
        span.setAttribute("score.ai.outcome", outcome);
        if (incomplete) span.setAttribute("score.ai.observation.incomplete", true);
        double duration = AiObservationTiming.elapsedMillis(startedNanos);
        Attributes attributes = Attributes.builder()
                .putAll(AiObservationLabels.providerModel(provider, model, outcome))
                .put("score.ai.response.finish_reason",
                        AiObservationLabels.finishReasonCategory(finishReason)).build();
        instruments.modelCalls.add(1, attributes);
        instruments.modelDuration.record(duration, attributes);
        instruments.genAiClientOperationDuration.record(
                AiObservationTiming.elapsedSeconds(startedNanos),
                GenAiSemanticConventions.inferenceAttributes(
                        GenAiSemanticConventions.CHAT, provider, model, responseModel,
                        failure != null ? failure.getClass().getName()
                                : !"success".equals(outcome) ? outcome : null));
        span.end();
    }

}
