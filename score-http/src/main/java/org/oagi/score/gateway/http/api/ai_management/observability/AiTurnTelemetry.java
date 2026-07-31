package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/** Owns preparation, admission, and terminal aggregation for one active AI turn. */
final class AiTurnTelemetry {

    private final AiTurnState state;
    private final AiObservationInstruments instruments;
    private final AiLifecycleEventObserver lifecycleEvents;
    private final AiObservationEvents events;
    private final ConcurrentMap<String, AiTurnState> turns;
    private final List<BiConsumer<String, String>> closeListeners;
    private final AiRequestModelResolver requestModels;

    AiTurnTelemetry(AiTurnState state, AiObservationInstruments instruments,
                    AiLifecycleEventObserver lifecycleEvents, AiObservationEvents events,
                    ConcurrentMap<String, AiTurnState> turns,
                    List<BiConsumer<String, String>> closeListeners,
                    AiRequestModelResolver requestModels) {
        this.state = state;
        this.instruments = instruments;
        this.lifecycleEvents = lifecycleEvents;
        this.events = events;
        this.turns = turns;
        this.closeListeners = closeListeners;
        this.requestModels = requestModels;
    }

    void executionStarted() {
        if (state == null || !state.reservePublication()) return;
        try {
            if (!state.executionStarted.compareAndSet(false, true)) return;
            double delay = AiObservationTiming.elapsedMillis(state.startedNanos);
            state.span.setAttribute("score.ai.queue_delay_ms", delay);
            instruments.queueDelay.record(delay, AiObservationLabels.model(state.model, null));
        } finally {
            state.releasePublication();
        }
    }

    void prepared(ChatRequest request) {
        if (state == null || request == null || !state.reservePublication()) return;
        try {
            String requestModel = requestModels.resolve(request.modelName());
            state.model = requestModel;
            state.conversationId = StringUtils.hasText(request.conversationId())
                    ? request.conversationId().strip() : null;
            state.span.setAttribute("gen_ai.request.model", requestModel);
            if (StringUtils.hasText(request.modelName())
                    && !request.modelName().strip().equals(requestModel)) {
                state.span.setAttribute("score.ai.model.alias", request.modelName().strip());
            }
            state.span.setAttribute("score.ai.conversation.id",
                    AiObservationLabels.value(request.conversationId()));
            if (state.conversationId != null) {
                state.span.setAttribute("gen_ai.conversation.id", state.conversationId);
            }
        } finally {
            state.releasePublication();
        }
    }

    void admissionRejected(String reason) {
        if (state == null || !state.reservePublication()) return;
        try {
            String normalized = AiObservationLabels.admissionReasonCategory(reason);
            state.span.setAttribute("score.ai.admission.reason", normalized);
            instruments.admissionRejections.add(1, Attributes.builder()
                    .put("gen_ai.request.model", AiObservationLabels.value(state.model))
                    .put("score.ai.admission.reason", normalized).build());
        } finally {
            state.releasePublication();
        }
    }

    void complete(String outcome, Throwable failure) {
        if (state == null || !state.beginClosing()) return;
        String normalized = AiLifecycleEventObserver.outcome(outcome);
        List<AiModelCallTelemetry> modelCalls;
        List<AiPlanOperationTelemetry> operations;
        synchronized (state) {
            modelCalls = List.copyOf(state.activeModelCalls);
            operations = List.copyOf(state.activeOperations);
        }
        modelCalls.forEach(call -> call.closeFromTurn(normalized));
        operations.forEach(operation -> operation.closeFromTurn(normalized));
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("outcome", normalized);
        if (failure != null) terminal.put("failure_type", failure.getClass().getSimpleName());
        ExecutionObservation closed = events.publish(ExecutionEventPublisher.REQUEST_CLOSED,
                events.scope(state.requestId, state.conversationId, null, state.generation,
                        ExecutionScope.Purpose.USER_RESPONSE), Map.copyOf(terminal));
        synchronized (state) {
            if (!state.ended.compareAndSet(false, true)) return;
            AiObservationEvents.terminalIdentity(state.span, closed);
            state.activeModelCalls.clear();
            state.activeOperations.clear();
            finishSpan(normalized, failure);
            lifecycleEvents.closeRequest(state.requestId, normalized);
            notifyCloseListeners(normalized);
            turns.remove(state.requestId, state);
            state.span.end();
        }
    }

    private void finishSpan(String outcome, Throwable failure) {
        if (failure != null) state.span.setAttribute("error.type", failure.getClass().getName());
        if (!"success".equals(outcome) && !"cancelled".equals(outcome)) {
            state.span.setStatus(StatusCode.ERROR, outcome);
            if (failure == null) state.span.setAttribute("error.type", outcome);
        }
        state.span.setAttribute("score.ai.outcome", outcome);
        state.span.setAttribute("score.ai.model_call_count", state.modelCalls.get());
        putPositive("gen_ai.usage.input_tokens", state.rootInputTokens.get());
        putPositive("gen_ai.usage.output_tokens", state.rootOutputTokens.get());
        if (state.rootCacheReadObserved.get()) {
            state.span.setAttribute("gen_ai.usage.cache_read.input_tokens",
                    state.rootCacheReadTokens.get());
        }
        if (state.rootCacheCreationObserved.get()) {
            state.span.setAttribute("gen_ai.usage.cache_creation.input_tokens",
                    state.rootCacheCreationTokens.get());
        }
        if (!state.rootFinishReasons.isEmpty()) {
            state.span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                    List.copyOf(state.rootFinishReasons));
        }
        instruments.turnDuration.record(AiObservationTiming.elapsedMillis(state.startedNanos),
                AiObservationLabels.model(state.model, outcome));
        Attributes standard = GenAiSemanticConventions.workflowDurationAttributes(
                state.workflowName,
                failure != null ? failure.getClass().getName()
                        : !"success".equals(outcome) && !"cancelled".equals(outcome)
                        ? outcome : null, false);
        instruments.genAiWorkflowDuration.record(
                AiObservationTiming.elapsedSeconds(state.startedNanos), standard);
        instruments.activeRequests.add(-1, state.activeRequestAttributes);
        instruments.turns.add(1, AiObservationLabels.model(state.model, outcome));
    }

    private void putPositive(String key, long value) {
        if (value > 0) state.span.setAttribute(key, value);
    }

    private void notifyCloseListeners(String outcome) {
        for (BiConsumer<String, String> listener : closeListeners) {
            try {
                listener.accept(state.requestId, outcome);
            } catch (RuntimeException ignored) {
                // Observability cleanup must never change the request outcome.
            }
        }
    }

}
