package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Request-scoped AI observability facade. It deliberately uses a private SDK and never
 * reads or writes OpenTelemetry's global provider.
 */
@Component
public final class ScoreAiObservability implements ExecutionObservationContext {

    static final String INSTRUMENTATION_SCOPE = "org.oagi.score.ai";
    private static final ContextKey<Boolean> PRIVATE_AI_CONTEXT =
            ContextKey.named("score.ai.private-context");
    private static final ContextKey<AtomicBoolean> AI_TURN_ENDED =
            ContextKey.named("score.ai.turn-ended");
    private static final TextMapGetter<Map<String, String>> TRACE_HEADERS =
            new TextMapGetter<>() {
                @Override public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }
                @Override public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

    private final String serviceVersion;
    private final Tracer tracer;
    private final AiObservationInstruments instruments;
    private final ConcurrentMap<String, TurnState> turns = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, String>> closeListeners = new CopyOnWriteArrayList<>();
    private final AiLifecycleEventObserver lifecycleEvents;

    @Autowired
    public ScoreAiObservability(ScoreAiObservabilitySdk sdk) {
        this(sdk.openTelemetry(), sdk.serviceVersion());
    }

    ScoreAiObservability(OpenTelemetry openTelemetry, String serviceVersion) {
        OpenTelemetry installed = Objects.requireNonNull(openTelemetry, "openTelemetry");
        this.serviceVersion = StringUtils.hasText(serviceVersion) ? serviceVersion : "unknown";
        this.tracer = installed.getTracer(INSTRUMENTATION_SCOPE);
        this.instruments = new AiObservationInstruments(installed.getMeter(INSTRUMENTATION_SCOPE));
        this.lifecycleEvents = new AiLifecycleEventObserver(tracer, instruments, this::parentContext);
    }

    public static ScoreAiObservability noop() {
        return new ScoreAiObservability(OpenTelemetry.noop(), "unknown");
    }

    /** Starts the root span. A valid inbound W3C trace context is continued when supplied. */
    public Turn startTurn(ChatRequest request, ScoreUser requester, long generation,
                          String traceparent, String tracestate) {
        Objects.requireNonNull(request, "request");
        return startExecution(new ExecutionDescriptor(request.requestId(), request.conversationId(),
                        request.modelName(), "assistant", request.permissionMode()),
                requester, generation, traceparent, tracestate);
    }

    /** Starts a generic AI execution without coupling non-chat features to {@link ChatRequest}. */
    public Turn startExecution(ExecutionDescriptor execution, ScoreUser requester, long generation,
                               String traceparent, String tracestate) {
        Objects.requireNonNull(execution, "execution");
        Context parent = extractedParent(traceparent, tracestate);
        SpanBuilder builder = tracer.spanBuilder("score.ai.turn").setParent(parent)
                .setAttribute("gen_ai.operation.name", "invoke_agent")
                .setAttribute("gen_ai.request.model", value(execution.model()))
                .setAttribute("score.ai.request.id", value(execution.requestId()))
                .setAttribute("score.ai.conversation.id", value(execution.conversationId()))
                .setAttribute("score.ai.execution.kind", value(execution.kind()))
                .setAttribute("score.ai.generation", generation)
                .setAttribute("score.ai.permission_mode", value(execution.permissionMode()));
        if (requester != null && requester.userId() != null) {
            builder.setAttribute("enduser.id", requester.userId().value().toString());
        }
        Span span = builder.startSpan();
        AtomicBoolean ended = new AtomicBoolean();
        TurnState state = new TurnState(execution.requestId(), execution.model(), span,
                privateContext(parent, span, ended), System.nanoTime(), ended);
        TurnState active = turns.putIfAbsent(execution.requestId(), state);
        if (active != null) {
            span.setAttribute("score.ai.duplicate_request_id", true);
            span.end();
            return new Turn(null);
        }
        instruments.activeRequests.add(1, state.activeRequestAttributes);
        return new Turn(state);
    }

    public record ExecutionDescriptor(String requestId, String conversationId, String model,
                                      String kind, String permissionMode) {
        public ExecutionDescriptor {
            if (!StringUtils.hasText(requestId)) {
                throw new IllegalArgumentException("An AI execution request ID is required.");
            }
        }
    }

    public void recordAdmissionRejection(ChatRequest request, ScoreUser requester,
                                         Throwable failure, String traceparent, String tracestate) {
        recordAdmissionRejection(request, requester, failure, "admission_failed",
                traceparent, tracestate);
    }

    public void recordAdmissionRejection(ChatRequest request, ScoreUser requester,
                                         Throwable failure, String reason,
                                         String traceparent, String tracestate) {
        if (request == null) return;
        String normalizedReason = admissionReasonCategory(reason);
        SpanBuilder builder = tracer.spanBuilder("score.ai.turn")
                .setParent(extractedParent(traceparent, tracestate))
                .setAttribute("gen_ai.operation.name", "invoke_agent")
                .setAttribute("gen_ai.request.model", value(request.modelName()))
                .setAttribute("score.ai.request.id", value(request.requestId()))
                .setAttribute("score.ai.conversation.id", value(request.conversationId()))
                .setAttribute("score.ai.outcome", "admission_rejected")
                .setAttribute("score.ai.admission.reason", normalizedReason);
        if (requester != null && requester.userId() != null) {
            builder.setAttribute("enduser.id", requester.userId().value().toString());
        }
        Span span = builder.startSpan();
        if (failure != null) {
            span.setAttribute("error.type", failure.getClass().getName());
        }
        span.setStatus(StatusCode.ERROR, "admission_rejected");
        Attributes labels = Attributes.builder()
                .putAll(modelAttributes(request.modelName(), "admission_rejected"))
                .put("score.ai.admission.reason", normalizedReason)
                .build();
        instruments.turns.add(1, labels);
        instruments.admissionRejections.add(1, labels);
        span.end();
    }

    public ModelCall startModelCall(String requestId, String model, String provider, String phase) {
        TurnState turn = requestId != null ? turns.get(requestId) : null;
        if (turn == null) return noopModelCall(model, provider);
        synchronized (turn) {
            if (turn.ended.get()) return noopModelCall(model, provider);
            Context parent = parentContext(requestId);
            ModelIdentity identity = turn.nextModelIdentity(parent);
            Span span = tracer.spanBuilder("score.ai.model")
                    .setParent(parent)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("gen_ai.operation.name", "chat")
                    .setAttribute("gen_ai.provider.name", value(provider))
                    .setAttribute("gen_ai.request.model", value(model))
                    .setAttribute("score.ai.model_call.id", identity.callId())
                    .setAttribute("score.ai.attempt", identity.attempt())
                    .setAttribute("score.ai.phase", value(phase))
                    .startSpan();
            ModelCall call = new ModelCall(turn, span, model, provider,
                    System.nanoTime(), true);
            turn.activeModelCalls.add(call);
            return call;
        }
    }

    /** Consumes content-minimized lifecycle facts emitted through {@code ExecutionObserver}. */
    void observe(String requestId, AiExecutionLifecycle event) {
        TurnState turn = requestId != null ? turns.get(requestId) : null;
        if (turn == null || event == null) return;
        synchronized (turn) {
            if (turn.ended.get() || turns.get(requestId) != turn) return;
            if ("provider_retry".equals(event.subtype())) {
                turn.retryScheduled(parentContext(requestId));
            }
            lifecycleEvents.observe(requestId, event);
        }
    }

    public void recordGuardrails(String requestId, String scope,
                                 List<GuardrailDecision> decisions) {
        recordGuardrails(requestId, scope, decisions, null);
    }

    public void recordGuardrails(String requestId, String scope,
                                 List<GuardrailDecision> decisions,
                                 GuardrailRefusal refusal) {
        whileActive(requestId, () -> recordActiveGuardrails(requestId, scope, decisions, refusal));
    }

    private void recordActiveGuardrails(String requestId, String scope,
                                        List<GuardrailDecision> decisions,
                                        GuardrailRefusal refusal) {
        List<GuardrailDecision> recorded = new java.util.ArrayList<>(
                decisions != null ? decisions : List.of());
        if (refusal != null && recorded.stream().noneMatch(decision ->
                decision.decisionId().equals(refusal.decision().decisionId()))) {
            recorded.add(refusal.decision());
        }
        for (GuardrailDecision decision : recorded) {
            if (decision == null) continue;
            String action = decision.action().name().toLowerCase();
            Attributes labels = Attributes.builder()
                    .put("score.ai.guardrail.scope", value(scope).toLowerCase())
                    .put("score.ai.guardrail.action", action)
                    .build();
            instruments.guardrailDecisions.add(1, labels);
            Span.fromContext(parentContext(requestId)).addEvent("score.ai.guardrail.decision",
                    Attributes.builder().putAll(labels)
                            .put("score.ai.guardrail.decision_id", decision.decisionId())
                            .put("score.ai.guardrail.policy_id", decision.policyId())
                            .put("score.ai.guardrail.policy_version", decision.policyVersion())
                            .build());
        }
    }

    /** Correlation fields persisted in ATIF extra_json; empty when observability is disabled. */
    public Map<String, Object> correlation(String requestId) {
        if (requestId == null) return Map.of();
        TurnState turn = turns.get(requestId);
        if (turn == null || turn.ended.get() || !turn.span.getSpanContext().isValid()) return Map.of();
        return Map.of(
                "trace_id", turn.span.getSpanContext().getTraceId(),
                "root_span_id", turn.span.getSpanContext().getSpanId(),
                "service_version", serviceVersion);
    }

    /** Records cost only when a provider or an explicit pricing engine supplies it. */
    public void recordCost(String provider, String model, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) return;
        instruments.cost.record(amount.doubleValue(), providerModelAttributes(provider, model, null));
    }

    Context parentContext(String requestId) {
        if (requestId == null) return Context.root();
        TurnState state = turns.get(requestId);
        if (state == null || state.ended.get()) return Context.root();
        Context current = Context.current();
        var currentSpan = Span.fromContext(current).getSpanContext();
        return Boolean.TRUE.equals(current.get(PRIVATE_AI_CONTEXT)) && currentSpan.isValid()
                && currentSpan.getTraceId().equals(state.span.getSpanContext().getTraceId())
                ? current : state.context;
    }

    static Context privateContext(Context parent, Span span) {
        return parent.with(span).with(PRIVATE_AI_CONTEXT, true);
    }

    private static Context privateContext(Context parent, Span span, AtomicBoolean ended) {
        return privateContext(parent, span).with(AI_TURN_ENDED, ended);
    }

    /** Prevents an already-ended private AI context from being propagated to MCP. */
    public static boolean currentContextCanPropagate() {
        Context current = Context.current();
        if (!Boolean.TRUE.equals(current.get(PRIVATE_AI_CONTEXT))) return true;
        AtomicBoolean ended = current.get(AI_TURN_ENDED);
        return ended == null || !ended.get();
    }

    public Scope makeAgentCurrent(String requestId, String runId) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null) return Scope.noop();
        synchronized (state) {
            if (state.ended.get()) return Scope.noop();
            Context context = state.agentContexts.get(runId);
            return context != null ? context.makeCurrent() : Scope.noop();
        }
    }

    @Override
    public ExecutionObservationContext.Activation makeToolCurrent(String requestId, String toolCallId) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || toolCallId == null) return () -> { };
        synchronized (state) {
            if (state.ended.get()) return () -> { };
            Context context = lifecycleEvents.toolContext(requestId, toolCallId);
            Scope scope = context != null ? context.makeCurrent() : Scope.noop();
            return scope::close;
        }
    }

    void registerAgentContext(String requestId, String runId, Context context) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null || context == null) return;
        synchronized (state) {
            if (!state.ended.get() && turns.get(requestId) == state) {
                state.agentContexts.put(runId, context);
            }
        }
    }

    void removeAgentContext(String requestId, String runId) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null) return;
        synchronized (state) {
            state.agentContexts.remove(runId);
        }
    }

    boolean isActive(String requestId) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        return state != null && !state.ended.get();
    }

    boolean whileActive(String requestId, Runnable action) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null) return false;
        synchronized (state) {
            if (state.ended.get() || turns.get(requestId) != state) return false;
            action.run();
            return true;
        }
    }

    Context agentParent(String requestId, String workflowNodeId) {
        if (!isActive(requestId)) return Context.root();
        Context workflow = lifecycleEvents.workflowContext(requestId, workflowNodeId);
        return workflow != null ? workflow : parentContext(requestId);
    }

    void onTurnClosed(BiConsumer<String, String> listener) {
        closeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    Tracer tracer() { return tracer; }
    AiObservationInstruments instruments() { return instruments; }

    private Context extractedParent(String traceparent, String tracestate) {
        if (!StringUtils.hasText(traceparent)) return Context.root();
        Map<String, String> carrier = new LinkedHashMap<>();
        carrier.put("traceparent", traceparent.strip());
        if (StringUtils.hasText(tracestate)) carrier.put("tracestate", tracestate.strip());
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, TRACE_HEADERS);
    }

    private static Attributes modelAttributes(String model, String outcome) {
        AttributesBuilder attributes = Attributes.builder()
                .put("gen_ai.request.model", value(model));
        if (outcome != null) attributes.put("score.ai.outcome", outcome);
        return attributes.build();
    }

    static Attributes providerModelAttributes(String provider, String model, String outcome) {
        AttributesBuilder attributes = Attributes.builder()
                .put("gen_ai.provider.name", value(provider))
                .put("gen_ai.request.model", value(model));
        if (outcome != null) attributes.put("score.ai.outcome", outcome);
        return attributes.build();
    }

    static String value(String value) {
        return StringUtils.hasText(value) ? value.strip() : "unknown";
    }

    private static double elapsedMillis(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toNanos()
                / 1_000_000.0;
    }

    private ModelCall noopModelCall(String model, String provider) {
        return new ModelCall(null, Span.getInvalid(), model, provider,
                System.nanoTime(), false);
    }

    private final class TurnState {
        private final String requestId;
        private volatile String model;
        private final Span span;
        private final Context context;
        private final long startedNanos;
        private final Attributes activeRequestAttributes;
        private final AtomicLong modelCalls = new AtomicLong();
        private final ConcurrentMap<String, ModelSequence> modelSequences = new ConcurrentHashMap<>();
        private final Set<ModelCall> activeModelCalls = new HashSet<>();
        private final Map<String, Context> agentContexts = new LinkedHashMap<>();
        private final AtomicBoolean firstToken = new AtomicBoolean();
        private final AtomicBoolean executionStarted = new AtomicBoolean();
        private final AtomicBoolean ended;

        private TurnState(String requestId, String model, Span span, Context context,
                          long startedNanos, AtomicBoolean ended) {
            this.requestId = requestId;
            this.model = model;
            this.span = span;
            this.context = context;
            this.startedNanos = startedNanos;
            this.ended = ended;
            this.activeRequestAttributes = modelAttributes(model, null);
        }

        private ModelIdentity nextModelIdentity(Context parent) {
            modelCalls.incrementAndGet();
            String parentSpanId = Span.fromContext(parent).getSpanContext().isValid()
                    ? Span.fromContext(parent).getSpanContext().getSpanId() : "root";
            return modelSequences.computeIfAbsent(parentSpanId, ignored -> new ModelSequence())
                    .next();
        }

        private void retryScheduled(Context parent) {
            String parentSpanId = Span.fromContext(parent).getSpanContext().isValid()
                    ? Span.fromContext(parent).getSpanContext().getSpanId() : "root";
            modelSequences.computeIfAbsent(parentSpanId, ignored -> new ModelSequence())
                    .retryScheduled();
        }
    }

    private record ModelIdentity(String callId, long attempt) { }

    private static final class ModelSequence {
        private String callId;
        private long attempt;
        private boolean retry;

        private synchronized ModelIdentity next() {
            if (!retry || callId == null) {
                callId = UUID.randomUUID().toString();
                attempt = 1L;
            } else {
                attempt++;
            }
            retry = false;
            return new ModelIdentity(callId, attempt);
        }

        private synchronized void retryScheduled() {
            retry = true;
        }
    }

    public final class Turn {
        private final TurnState state;

        private Turn(TurnState state) { this.state = state; }

        public void executionStarted() {
            if (state == null || !state.executionStarted.compareAndSet(false, true)) return;
            double delay = elapsedMillis(state.startedNanos);
            state.span.setAttribute("score.ai.queue_delay_ms", delay);
            instruments.queueDelay.record(delay, modelAttributes(state.model, null));
        }

        public void prepared(ChatRequest request) {
            if (state == null || request == null || state.ended.get()) return;
            state.model = value(request.modelName());
            state.span.setAttribute("gen_ai.request.model", value(request.modelName()));
            state.span.setAttribute("score.ai.conversation.id", value(request.conversationId()));
        }

        public void admissionRejected(String reason) {
            if (state == null || state.ended.get()) return;
            String normalized = admissionReasonCategory(reason);
            state.span.setAttribute("score.ai.admission.reason", normalized);
            instruments.admissionRejections.add(1, Attributes.builder()
                    .put("gen_ai.request.model", value(state.model))
                    .put("score.ai.admission.reason", normalized)
                    .build());
        }

        public void complete(String outcome, Throwable failure) {
            if (state == null) return;
            synchronized (state) {
                if (!state.ended.compareAndSet(false, true)) return;
                String normalized = AiLifecycleEventObserver.outcome(outcome);
                for (ModelCall modelCall : List.copyOf(state.activeModelCalls)) {
                    modelCall.closeFromTurn(normalized);
                }
                state.activeModelCalls.clear();
                if (failure != null) {
                    state.span.setAttribute("error.type", failure.getClass().getName());
                }
                if (!"success".equals(normalized) && !"cancelled".equals(normalized)) {
                    state.span.setStatus(StatusCode.ERROR, normalized);
                }
                state.span.setAttribute("score.ai.outcome", normalized);
                state.span.setAttribute("score.ai.model_call_count", state.modelCalls.get());
                double duration = elapsedMillis(state.startedNanos);
                instruments.turnDuration.record(duration, modelAttributes(state.model, normalized));
                instruments.activeRequests.add(-1, state.activeRequestAttributes);
                instruments.turns.add(1, modelAttributes(state.model, normalized));
                lifecycleEvents.closeRequest(state.requestId, normalized);
                for (BiConsumer<String, String> listener : closeListeners) {
                    try {
                        listener.accept(state.requestId, normalized);
                    } catch (RuntimeException ignored) {
                        // Observability cleanup must never change the request outcome.
                    }
                }
                turns.remove(state.requestId, state);
                state.span.end();
            }
        }
    }

    public final class ModelCall {
        private final TurnState turn;
        private final Span span;
        private final String model;
        private final String provider;
        private final long startedNanos;
        private final AtomicBoolean firstToken = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final boolean recording;
        private String finishReason = "unknown";

        private ModelCall(TurnState turn, Span span, String model, String provider,
                          long startedNanos, boolean recording) {
            this.turn = turn;
            this.span = span;
            this.model = model;
            this.provider = provider;
            this.startedNanos = startedNanos;
            this.recording = recording;
        }

        public void firstToken() {
            if (!recording) return;
            synchronized (turn) {
                if (turn.ended.get() || ended.get() || !firstToken.compareAndSet(false, true)) return;
                double duration = elapsedMillis(startedNanos);
                span.setAttribute("score.ai.time_to_first_token_ms", duration);
                if (turn.firstToken.compareAndSet(false, true)) {
                    double turnTtft = elapsedMillis(turn.startedNanos);
                    turn.span.setAttribute("score.ai.time_to_first_token_ms", turnTtft);
                    instruments.timeToFirstToken.record(turnTtft,
                            providerModelAttributes(provider, model, null));
                }
            }
        }

        public void complete(ChatResponse response) {
            if (!recording) return;
            synchronized (turn) {
                if (!ended.compareAndSet(false, true)) return;
                RuntimeException observationFailure = null;
                try {
                    if (response != null) {
                        if (StringUtils.hasText(response.getMetadata().getId())) {
                            span.setAttribute("gen_ai.response.id", response.getMetadata().getId());
                        }
                        if (StringUtils.hasText(response.getMetadata().getModel())) {
                            span.setAttribute("gen_ai.response.model", response.getMetadata().getModel());
                        }
                        response.getResults().stream().findFirst().ifPresent(generation -> {
                            String reason = generation.getMetadata().getFinishReason();
                            if (StringUtils.hasText(reason)) {
                                finishReason = finishReasonCategory(reason);
                                span.setAttribute("gen_ai.response.finish_reason", finishReason);
                            }
                        });
                        recordTokens(response.getMetadata().getUsage());
                        recordProviderCost(response);
                    }
                } catch (RuntimeException failure) {
                    // Observation must never turn a successful provider response into an app failure.
                    observationFailure = failure;
                }
                finishLocked(observationFailure == null ? "success" : "error",
                        observationFailure, false);
            }
        }

        public void fail(Throwable failure) {
            if (!recording) return;
            synchronized (turn) {
                if (ended.compareAndSet(false, true)) finishLocked("error", failure, false);
            }
        }

        public void cancel() {
            if (!recording) return;
            synchronized (turn) {
                if (ended.compareAndSet(false, true)) finishLocked("cancelled", null, false);
            }
        }

        private void recordTokens(Usage usage) {
            if (usage == null) return;
            token("input", usage.getPromptTokens());
            token("output", usage.getCompletionTokens());
            token("cache_read", usage.getCacheReadInputTokens());
            token("cache_write", usage.getCacheWriteInputTokens());
        }

        private void token(String type, Number count) {
            if (count == null || count.longValue() < 0) return;
            Attributes attributes = Attributes.builder()
                    .putAll(providerModelAttributes(provider, model, null))
                    .put("gen_ai.token.type", type)
                    .build();
            instruments.tokens.add(count.longValue(), attributes);
            span.setAttribute("gen_ai.usage." + type + "_tokens", count.longValue());
        }

        private void recordProviderCost(ChatResponse response) {
            Object reported = response.getMetadata().get("cost_usd");
            if (reported == null) reported = response.getMetadata().get("costUsd");
            if (reported == null && response.getMetadata().getUsage() != null
                    && response.getMetadata().getUsage().getNativeUsage() instanceof Map<?, ?> nativeUsage) {
                reported = nativeUsage.get("cost_usd");
                if (reported == null) reported = nativeUsage.get("costUsd");
            }
            BigDecimal cost = nonNegativeDecimal(reported);
            if (cost == null) return;
            recordCost(provider, model, cost);
            span.setAttribute("score.ai.cost_usd", cost.doubleValue());
        }

        private void closeFromTurn(String turnOutcome) {
            if (!ended.compareAndSet(false, true)) return;
            String outcome = switch (turnOutcome) {
                case "cancelled", "timeout" -> turnOutcome;
                default -> "error";
            };
            finishLocked(outcome, null, true);
        }

        private void finishLocked(String outcome, Throwable failure, boolean incomplete) {
            if (turn != null) turn.activeModelCalls.remove(this);
            if (failure != null) {
                span.setAttribute("error.type", failure.getClass().getName());
                span.setStatus(StatusCode.ERROR, outcome);
            } else if (!"success".equals(outcome)) {
                span.setStatus(StatusCode.ERROR, outcome);
            }
            span.setAttribute("score.ai.outcome", outcome);
            if (incomplete) span.setAttribute("score.ai.observation.incomplete", true);
            double duration = elapsedMillis(startedNanos);
            Attributes attributes = Attributes.builder()
                    .putAll(providerModelAttributes(provider, model, outcome))
                    .put("gen_ai.response.finish_reason", finishReasonCategory(finishReason))
                    .build();
            instruments.modelCalls.add(1, attributes);
            instruments.modelDuration.record(duration, attributes);
            span.end();
        }
    }

    private static String finishReasonCategory(String reason) {
        String normalized = AiObservationInstruments.normalized(reason);
        return switch (normalized) {
            case "stop", "end_turn", "stop_sequence" -> "stop";
            case "length", "max_tokens" -> "length";
            case "tool_calls", "tool_use" -> "tool_calls";
            case "content_filter", "refusal" -> "content_filter";
            case "error" -> "error";
            default -> "unknown";
        };
    }

    private static String admissionReasonCategory(String reason) {
        String normalized = AiObservationInstruments.normalized(reason);
        return switch (normalized) {
            case "registry_capacity", "user_limit", "conversation_busy", "duplicate_request",
                 "validation", "preparation_failed", "executor_rejected",
                 "transport_send_failed", "admission_failed" -> normalized;
            default -> "other";
        };
    }

    private static BigDecimal nonNegativeDecimal(Object value) {
        if (value == null) return null;
        try {
            String text = value.toString().strip();
            if (text.length() > 40 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
            BigDecimal amount = new BigDecimal(text);
            return amount.signum() >= 0 ? amount : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
