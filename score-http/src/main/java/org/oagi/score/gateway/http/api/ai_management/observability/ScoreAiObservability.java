package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.AttributeKey;
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
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
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
import java.util.function.Function;

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
    private static final ContextKey<String> ACTIVE_AGENT_RUN_ID =
            ContextKey.named("score.ai.active-agent-run-id");
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
    private final Function<String, String> requestModelResolver;
    private final ConcurrentMap<String, TurnState> turns = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, String>> closeListeners = new CopyOnWriteArrayList<>();
    private final AiLifecycleEventObserver lifecycleEvents;

    @Autowired
    public ScoreAiObservability(ScoreAiObservabilitySdk sdk, ScoreAiModelRegistry models) {
        this(sdk.openTelemetry(), sdk.serviceVersion(), alias ->
                models.modelConfiguration(alias).model());
    }

    ScoreAiObservability(OpenTelemetry openTelemetry, String serviceVersion) {
        this(openTelemetry, serviceVersion, Function.identity());
    }

    ScoreAiObservability(OpenTelemetry openTelemetry, String serviceVersion,
                         Function<String, String> requestModelResolver) {
        OpenTelemetry installed = Objects.requireNonNull(openTelemetry, "openTelemetry");
        this.serviceVersion = StringUtils.hasText(serviceVersion) ? serviceVersion : "unknown";
        this.tracer = installed.getTracer(INSTRUMENTATION_SCOPE);
        this.instruments = new AiObservationInstruments(installed.getMeter(INSTRUMENTATION_SCOPE));
        this.requestModelResolver = Objects.requireNonNull(
                requestModelResolver, "requestModelResolver");
        this.lifecycleEvents = new AiLifecycleEventObserver(
                tracer, instruments, this::parentContext, this::activeAgentName);
    }

    public static ScoreAiObservability noop() {
        return new ScoreAiObservability(OpenTelemetry.noop(), "unknown");
    }

    /** Starts the root span. A valid inbound W3C trace context is continued when supplied. */
    public Turn startTurn(ChatRequest request, ScoreUser requester, long generation,
                          String traceparent, String tracestate) {
        Objects.requireNonNull(request, "request");
        return startExecution(new ExecutionDescriptor(request.requestId(), request.conversationId(),
                        request.modelName(), "assistant", request.permissionMode(),
                        request.reasoningEffort()),
                requester, generation, traceparent, tracestate);
    }

    /** Starts a generic AI execution without coupling non-chat features to {@link ChatRequest}. */
    public Turn startExecution(ExecutionDescriptor execution, ScoreUser requester, long generation,
                               String traceparent, String tracestate) {
        Objects.requireNonNull(execution, "execution");
        Context parent = extractedParent(traceparent, tracestate);
        String agentName = value(execution.kind());
        String requestModel = requestModel(execution.model());
        SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                        GenAiSemanticConventions.INVOKE_AGENT, agentName)).setParent(parent)
                .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.INVOKE_AGENT)
                .setAttribute("gen_ai.agent.name", agentName)
                .setAttribute("gen_ai.request.model", requestModel)
                .setAttribute("score.ai.request.id", value(execution.requestId()))
                .setAttribute("score.ai.conversation.id", value(execution.conversationId()))
                .setAttribute("score.ai.execution.kind", value(execution.kind()))
                .setAttribute("score.ai.generation", generation)
                .setAttribute("score.ai.permission_mode", value(execution.permissionMode()));
        putModelAlias(builder, execution.model(), requestModel);
        GenAiSemanticConventions.putIfKnown(builder, "gen_ai.request.reasoning.level",
                execution.reasoningLevel());
        if (StringUtils.hasText(execution.conversationId())) {
            builder.setAttribute("gen_ai.conversation.id", execution.conversationId().strip());
        }
        if (requester != null && requester.userId() != null) {
            builder.setAttribute("enduser.id", requester.userId().value().toString());
        }
        Span span = builder.startSpan();
        AtomicBoolean ended = new AtomicBoolean();
        TurnState state = new TurnState(execution.requestId(), execution.conversationId(),
                requestModel, execution.reasoningLevel(), agentName, span,
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
                                      String kind, String permissionMode, String reasoningLevel) {
        public ExecutionDescriptor(String requestId, String conversationId, String model,
                                   String kind, String permissionMode) {
            this(requestId, conversationId, model, kind, permissionMode, null);
        }

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
        long startedNanos = System.nanoTime();
        String normalizedReason = admissionReasonCategory(reason);
        String requestModel = requestModel(request.modelName());
        SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                        GenAiSemanticConventions.INVOKE_AGENT, "assistant"))
                .setParent(extractedParent(traceparent, tracestate))
                .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.INVOKE_AGENT)
                .setAttribute("gen_ai.agent.name", "assistant")
                .setAttribute("gen_ai.request.model", requestModel)
                .setAttribute("score.ai.request.id", value(request.requestId()))
                .setAttribute("score.ai.conversation.id", value(request.conversationId()))
                .setAttribute("score.ai.outcome", "admission_rejected")
                .setAttribute("score.ai.admission.reason", normalizedReason);
        putModelAlias(builder, request.modelName(), requestModel);
        if (StringUtils.hasText(request.conversationId())) {
            builder.setAttribute("gen_ai.conversation.id", request.conversationId().strip());
        }
        if (requester != null && requester.userId() != null) {
            builder.setAttribute("enduser.id", requester.userId().value().toString());
        }
        Span span = builder.startSpan();
        if (failure != null) {
            span.setAttribute("error.type", failure.getClass().getName());
        }
        span.setStatus(StatusCode.ERROR, "admission_rejected");
        Attributes labels = Attributes.builder()
                .putAll(modelAttributes(requestModel, "admission_rejected"))
                .put("score.ai.admission.reason", normalizedReason)
                .build();
        instruments.turns.add(1, labels);
        instruments.admissionRejections.add(1, labels);
        Attributes standard = GenAiSemanticConventions.agentDurationAttributes(
                "assistant", requestModel,
                failure != null ? failure.getClass().getName() : "admission_rejected");
        instruments.genAiInvokeAgentDuration.record(
                GenAiSemanticConventions.elapsedSeconds(startedNanos), standard);
        Attributes calls = GenAiSemanticConventions.agentCallAttributes("assistant");
        instruments.genAiInvokeAgentInferenceCalls.record(0, calls);
        instruments.genAiInvokeAgentToolCalls.record(0, calls);
        span.end();
    }

    public ModelCall startModelCall(String requestId, String model, String provider, String phase) {
        return startModelCall(requestId, model, model, provider, phase);
    }

    public ModelCall startModelCall(String requestId, String modelAlias, String requestModel,
                                    String provider, String phase) {
        String semanticModel = StringUtils.hasText(requestModel)
                ? requestModel.strip() : value(modelAlias);
        TurnState turn = requestId != null ? turns.get(requestId) : null;
        if (turn == null) return noopModelCall(semanticModel, provider);
        synchronized (turn) {
            if (turn.ended.get()) return noopModelCall(semanticModel, provider);
            String semanticProvider = GenAiSemanticConventions.providerName(provider);
            Context parent = parentContext(requestId);
            ModelIdentity identity = turn.nextModelIdentity(parent);
            SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                            GenAiSemanticConventions.CHAT, semanticModel))
                    .setParent(parent)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.CHAT)
                    .setAttribute("gen_ai.provider.name", value(semanticProvider))
                    .setAttribute("gen_ai.request.model", semanticModel)
                    .setAttribute("score.ai.model_call.id", identity.callId())
                    .setAttribute("score.ai.attempt", identity.attempt())
                    .setAttribute("score.ai.phase", value(phase));
            putModelAlias(builder, modelAlias, semanticModel);
            if (StringUtils.hasText(turn.conversationId)) {
                builder.setAttribute("gen_ai.conversation.id", turn.conversationId);
            }
            GenAiSemanticConventions.putIfKnown(builder, "gen_ai.request.reasoning.level",
                    turn.reasoningLevel);
            if (turn.compacted.get()) {
                builder.setAttribute("gen_ai.conversation.compacted", true);
            }
            Span span = builder.startSpan();
            ModelCall call = new ModelCall(turn, span, semanticModel, semanticProvider,
                    identity.agentInvocation(), identity.sequence(),
                    System.nanoTime(), true);
            turn.activeModelCalls.add(call);
            return call;
        }
    }

    private String requestModel(String alias) {
        String fallback = value(alias);
        try {
            String resolved = requestModelResolver.apply(alias);
            return StringUtils.hasText(resolved) ? resolved.strip() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static void putModelAlias(SpanBuilder builder, String alias, String requestModel) {
        if (StringUtils.hasText(alias) && !alias.strip().equals(requestModel)) {
            builder.setAttribute("score.ai.model.alias", alias.strip());
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
            if ("context_compacted".equals(event.subtype())) {
                turn.compacted.set(true);
                turn.span.setAttribute("gen_ai.conversation.compacted", true);
            }
            if ("tool_call".equals(event.eventType()) && "started".equals(event.subtype())) {
                turn.recordToolCall(parentContext(requestId));
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
        if (Boolean.TRUE.equals(current.get(PRIVATE_AI_CONTEXT)) && currentSpan.isValid()
                && currentSpan.getTraceId().equals(state.span.getSpanContext().getTraceId())
        ) {
            return current;
        }
        // Reactive provider/tool callbacks do not consistently preserve thread-local Context.
        // A single active agent is unambiguous, so keep its model/tool work nested under it.
        return state.agentContexts.size() == 1
                ? state.agentContexts.values().iterator().next() : state.context;
    }

    private String activeAgentName(String requestId) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || state.ended.get()) return null;
        Context current = Context.current();
        String activeRunId = current.get(ACTIVE_AGENT_RUN_ID);
        AgentInvocation attributed = activeRunId != null
                ? state.agentInvocations.get(activeRunId) : null;
        if (attributed != null) return attributed.agentName;
        String currentSpanId = Span.fromContext(current).getSpanContext().getSpanId();
        return state.agentInvocations.values().stream()
                .filter(invocation -> Span.fromContext(invocation.context).getSpanContext()
                        .getSpanId().equals(currentSpanId))
                .map(invocation -> invocation.agentName)
                .findFirst()
                .orElseGet(() -> state.agentInvocations.size() == 1
                        ? state.agentInvocations.values().iterator().next().agentName : null);
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

    @Override
    public ExecutionObservationContext.Operation startPlan(String requestId, String agentName) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null) return ExecutionObservationContext.Operation.noop();
        synchronized (state) {
            if (state.ended.get() || turns.get(requestId) != state) {
                return ExecutionObservationContext.Operation.noop();
            }
            String semanticTarget = value(agentName);
            Context parent = parentContext(requestId);
            SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                            GenAiSemanticConventions.PLAN, semanticTarget))
                    .setParent(parent)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.PLAN);
            GenAiSemanticConventions.putIfKnown(
                    builder, "gen_ai.agent.name", semanticTarget);
            Span span = builder.startSpan();
            ObservedOperation observed = new ObservedOperation(
                    state, span, privateContext(parent, span));
            state.activeOperations.add(observed);
            observed.activate();
            return observed;
        }
    }

    void registerAgentContext(String requestId, String runId, String agentName, Context context) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null || context == null) return;
        synchronized (state) {
            if (!state.ended.get() && turns.get(requestId) == state) {
                Context attributed = context.with(ACTIVE_AGENT_RUN_ID, runId);
                state.agentContexts.put(runId, attributed);
                state.agentInvocations.put(runId, new AgentInvocation(agentName, attributed));
            }
        }
    }

    AgentInvocationCounts removeAgentContext(String requestId, String runId) {
        TurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null) return AgentInvocationCounts.EMPTY;
        synchronized (state) {
            state.agentContexts.remove(runId);
            AgentInvocation invocation = state.agentInvocations.remove(runId);
            return invocation != null ? invocation.snapshot() : AgentInvocationCounts.EMPTY;
        }
    }

    record AgentInvocationCounts(long inferenceCalls, long toolCalls,
                                 AgentUsage usage, List<String> finishReasons) {
        private static final AgentInvocationCounts EMPTY =
                new AgentInvocationCounts(0, 0, AgentUsage.EMPTY, List.of());
    }

    record AgentUsage(long inputTokens, long outputTokens,
                      long cacheReadTokens, boolean cacheReadObserved,
                      long cacheCreationTokens, boolean cacheCreationObserved) {
        private static final AgentUsage EMPTY = new AgentUsage(0, 0, 0, false, 0, false);
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

    Context agentParent(String requestId, String workflowNodeId, String workflowParentNodeId) {
        if (!isActive(requestId)) return Context.root();
        Context workflow = lifecycleEvents.workflowContext(requestId, workflowNodeId);
        if (workflow != null) return workflow;
        workflow = lifecycleEvents.workflowContext(requestId, workflowParentNodeId);
        if (workflow != null) return workflow;
        return parentContext(requestId);
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
        return new ModelCall(null, Span.getInvalid(), model,
                GenAiSemanticConventions.providerName(provider), null, 0,
                System.nanoTime(), false);
    }

    private final class TurnState {
        private final String requestId;
        private volatile String model;
        private final String reasoningLevel;
        private volatile String conversationId;
        private final String agentName;
        private final Span span;
        private final Context context;
        private final long startedNanos;
        private final Attributes activeRequestAttributes;
        private final AtomicLong modelCalls = new AtomicLong();
        private final AtomicLong rootInferenceCalls = new AtomicLong();
        private final AtomicLong rootToolCalls = new AtomicLong();
        private final AtomicLong rootInputTokens = new AtomicLong();
        private final AtomicLong rootOutputTokens = new AtomicLong();
        private final AtomicLong rootCacheReadTokens = new AtomicLong();
        private final AtomicBoolean rootCacheReadObserved = new AtomicBoolean();
        private final AtomicLong rootCacheCreationTokens = new AtomicLong();
        private final AtomicBoolean rootCacheCreationObserved = new AtomicBoolean();
        private final AtomicLong rootFinishReasonSequence = new AtomicLong();
        private volatile List<String> rootFinishReasons = List.of();
        private final ConcurrentMap<String, ModelSequence> modelSequences = new ConcurrentHashMap<>();
        private final Set<ModelCall> activeModelCalls = new HashSet<>();
        private final ConcurrentMap<String, Context> agentContexts = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AgentInvocation> agentInvocations =
                new ConcurrentHashMap<>();
        private final Set<ObservedOperation> activeOperations = new HashSet<>();
        private final AtomicBoolean firstToken = new AtomicBoolean();
        private final AtomicBoolean executionStarted = new AtomicBoolean();
        private final AtomicBoolean compacted = new AtomicBoolean();
        private final AtomicBoolean ended;

        private TurnState(String requestId, String conversationId, String model,
                          String reasoningLevel, String agentName,
                          Span span, Context context,
                          long startedNanos, AtomicBoolean ended) {
            this.requestId = requestId;
            this.conversationId = StringUtils.hasText(conversationId)
                    ? conversationId.strip() : null;
            this.model = model;
            this.reasoningLevel = reasoningLevel;
            this.agentName = agentName;
            this.span = span;
            this.context = context;
            this.startedNanos = startedNanos;
            this.ended = ended;
            this.activeRequestAttributes = modelAttributes(model, null);
        }

        private ModelIdentity nextModelIdentity(Context parent) {
            long sequence = modelCalls.incrementAndGet();
            java.util.Optional<AgentInvocation> invocation = agentInvocation(parent);
            invocation.ifPresentOrElse(
                    activeAgent -> activeAgent.inferenceCalls.incrementAndGet(),
                    rootInferenceCalls::incrementAndGet);
            String parentSpanId = Span.fromContext(parent).getSpanContext().isValid()
                    ? Span.fromContext(parent).getSpanContext().getSpanId() : "root";
            return modelSequences.computeIfAbsent(parentSpanId, ignored -> new ModelSequence())
                    .next(invocation.orElse(null), sequence);
        }

        private void recordFinishReasons(long sequence, List<String> reasons) {
            if (sequence >= rootFinishReasonSequence.getAndAccumulate(sequence, Math::max)) {
                rootFinishReasons = List.copyOf(reasons);
            }
        }

        private void recordToolCall(Context parent) {
            agentInvocation(parent).ifPresentOrElse(
                    invocation -> invocation.toolCalls.incrementAndGet(),
                    rootToolCalls::incrementAndGet);
        }

        private java.util.Optional<AgentInvocation> agentInvocation(Context parent) {
            String activeRunId = parent.get(ACTIVE_AGENT_RUN_ID);
            AgentInvocation attributed = activeRunId != null
                    ? agentInvocations.get(activeRunId) : null;
            if (attributed != null) return java.util.Optional.of(attributed);
            String parentSpanId = Span.fromContext(parent).getSpanContext().getSpanId();
            return agentInvocations.values().stream().filter(invocation ->
                    Span.fromContext(invocation.context).getSpanContext().getSpanId()
                            .equals(parentSpanId)).findFirst();
        }

        private void retryScheduled(Context parent) {
            String parentSpanId = Span.fromContext(parent).getSpanContext().isValid()
                    ? Span.fromContext(parent).getSpanContext().getSpanId() : "root";
            modelSequences.computeIfAbsent(parentSpanId, ignored -> new ModelSequence())
                    .retryScheduled();
        }
    }

    private static final class AgentInvocation {
        private final String agentName;
        private final Context context;
        private final AtomicLong inferenceCalls = new AtomicLong();
        private final AtomicLong toolCalls = new AtomicLong();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cacheReadTokens = new AtomicLong();
        private final AtomicBoolean cacheReadObserved = new AtomicBoolean();
        private final AtomicLong cacheCreationTokens = new AtomicLong();
        private final AtomicBoolean cacheCreationObserved = new AtomicBoolean();
        private final AtomicLong finishReasonSequence = new AtomicLong();
        private volatile List<String> finishReasons = List.of();

        private AgentInvocation(String agentName, Context context) {
            this.agentName = agentName;
            this.context = context;
        }

        private AgentInvocationCounts snapshot() {
            return new AgentInvocationCounts(inferenceCalls.get(), toolCalls.get(),
                    new AgentUsage(inputTokens.get(), outputTokens.get(),
                            cacheReadTokens.get(), cacheReadObserved.get(),
                            cacheCreationTokens.get(), cacheCreationObserved.get()),
                    List.copyOf(finishReasons));
        }

        private void recordFinishReasons(long sequence, List<String> reasons) {
            if (sequence >= finishReasonSequence.getAndAccumulate(sequence, Math::max)) {
                finishReasons = List.copyOf(reasons);
            }
        }
    }

    private record ModelIdentity(String callId, long attempt,
                                 AgentInvocation agentInvocation, long sequence) { }

    private static final class ModelSequence {
        private String callId;
        private long attempt;
        private boolean retry;

        private synchronized ModelIdentity next(AgentInvocation agentInvocation, long sequence) {
            if (!retry || callId == null) {
                callId = UUID.randomUUID().toString();
                attempt = 1L;
            } else {
                attempt++;
            }
            retry = false;
            return new ModelIdentity(callId, attempt, agentInvocation, sequence);
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
            String requestModel = requestModel(request.modelName());
            state.model = requestModel;
            state.conversationId = StringUtils.hasText(request.conversationId())
                    ? request.conversationId().strip() : null;
            state.span.setAttribute("gen_ai.request.model", requestModel);
            if (StringUtils.hasText(request.modelName())
                    && !request.modelName().strip().equals(requestModel)) {
                state.span.setAttribute("score.ai.model.alias", request.modelName().strip());
            }
            state.span.setAttribute("score.ai.conversation.id", value(request.conversationId()));
            if (state.conversationId != null) {
                state.span.setAttribute("gen_ai.conversation.id", state.conversationId);
            }
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
                for (ObservedOperation operation : List.copyOf(state.activeOperations)) {
                    operation.closeFromTurn(normalized);
                }
                state.activeOperations.clear();
                if (failure != null) {
                    state.span.setAttribute("error.type", failure.getClass().getName());
                }
                if (!"success".equals(normalized) && !"cancelled".equals(normalized)) {
                    state.span.setStatus(StatusCode.ERROR, normalized);
                    if (failure == null) state.span.setAttribute("error.type", normalized);
                }
                state.span.setAttribute("score.ai.outcome", normalized);
                state.span.setAttribute("score.ai.model_call_count", state.modelCalls.get());
                if (state.rootInputTokens.get() > 0) {
                    state.span.setAttribute("gen_ai.usage.input_tokens",
                            state.rootInputTokens.get());
                }
                if (state.rootOutputTokens.get() > 0) {
                    state.span.setAttribute("gen_ai.usage.output_tokens",
                            state.rootOutputTokens.get());
                }
                if (state.rootCacheReadObserved.get()) {
                    state.span.setAttribute("gen_ai.usage.cache_read.input_tokens",
                            state.rootCacheReadTokens.get());
                }
                if (state.rootCacheCreationObserved.get()) {
                    state.span.setAttribute("gen_ai.usage.cache_creation.input_tokens",
                            state.rootCacheCreationTokens.get());
                }
                if (!state.rootFinishReasons.isEmpty()) {
                    state.span.setAttribute(AttributeKey.stringArrayKey(
                            "gen_ai.response.finish_reasons"),
                            List.copyOf(state.rootFinishReasons));
                }
                double duration = elapsedMillis(state.startedNanos);
                instruments.turnDuration.record(duration, modelAttributes(state.model, normalized));
                Attributes standard = GenAiSemanticConventions.agentDurationAttributes(
                        state.agentName, state.model,
                        failure != null ? failure.getClass().getName()
                                : !"success".equals(normalized) && !"cancelled".equals(normalized)
                                ? normalized : null);
                instruments.genAiInvokeAgentDuration.record(
                        GenAiSemanticConventions.elapsedSeconds(state.startedNanos), standard);
                Attributes calls = GenAiSemanticConventions.agentCallAttributes(state.agentName);
                instruments.genAiInvokeAgentInferenceCalls.record(
                        state.rootInferenceCalls.get(), calls);
                instruments.genAiInvokeAgentToolCalls.record(state.rootToolCalls.get(), calls);
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

    private final class ObservedOperation implements ExecutionObservationContext.Operation {
        private final TurnState turn;
        private final Span span;
        private final Context context;
        private final AtomicBoolean ended = new AtomicBoolean();
        private Scope scope = Scope.noop();
        private volatile Throwable failure;
        private volatile boolean cancelled;

        private ObservedOperation(TurnState turn, Span span, Context context) {
            this.turn = turn;
            this.span = span;
            this.context = context;
        }

        private void activate() {
            scope = context.makeCurrent();
        }

        @Override
        public void fail(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void close() {
            scope.close();
            finish(cancelled ? "cancelled" : failure != null ? "error" : "success",
                    failure, false);
        }

        private void closeFromTurn(String outcome) {
            finish(outcome, null, true);
        }

        private void finish(String outcome, Throwable cause, boolean incomplete) {
            if (!ended.compareAndSet(false, true)) return;
            String normalized = AiLifecycleEventObserver.outcome(outcome);
            String errorType = cause != null ? cause.getClass().getName()
                    : !"success".equals(normalized) && !"cancelled".equals(normalized)
                    ? normalized : null;
            span.setAttribute("score.ai.outcome", normalized);
            if (incomplete) span.setAttribute("score.ai.observation.incomplete", true);
            if (errorType != null) {
                span.setAttribute("error.type", errorType);
                span.setStatus(StatusCode.ERROR, normalized);
            }
            synchronized (turn) {
                turn.activeOperations.remove(this);
            }
            span.end();
        }
    }

    public final class ModelCall {
        private final TurnState turn;
        private final Span span;
        private final String model;
        private final String provider;
        private final AgentInvocation agentInvocation;
        private final long sequence;
        private final long startedNanos;
        private final AtomicBoolean firstChunk = new AtomicBoolean();
        private final AtomicBoolean firstToken = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final boolean recording;
        private String finishReason = "unknown";
        private String responseModel;

        private ModelCall(TurnState turn, Span span, String model, String provider,
                          AgentInvocation agentInvocation, long sequence,
                          long startedNanos, boolean recording) {
            this.turn = turn;
            this.span = span;
            this.model = model;
            this.provider = provider;
            this.agentInvocation = agentInvocation;
            this.sequence = sequence;
            this.startedNanos = startedNanos;
            this.recording = recording;
        }

        public void streaming() {
            if (recording && !turn.ended.get() && !ended.get()) {
                span.setAttribute("gen_ai.request.stream", true);
            }
        }

        public void firstChunk() {
            if (!recording) return;
            synchronized (turn) {
                if (turn.ended.get() || ended.get() || !firstChunk.compareAndSet(false, true)) {
                    return;
                }
                double duration = elapsedMillis(startedNanos);
                span.setAttribute("gen_ai.response.time_to_first_chunk", duration / 1_000.0);
                instruments.genAiClientTimeToFirstChunk.record(
                        GenAiSemanticConventions.elapsedSeconds(startedNanos),
                        GenAiSemanticConventions.inferenceAttributes(
                                GenAiSemanticConventions.CHAT, provider, model, responseModel, null));
            }
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
                            responseModel = response.getMetadata().getModel().strip();
                            span.setAttribute("gen_ai.response.model", responseModel);
                        }
                        List<String> finishReasons = response.getResults().stream()
                                .map(generation -> generation.getMetadata().getFinishReason())
                                .filter(StringUtils::hasText)
                                .map(ScoreAiObservability::finishReasonValue)
                                .toList();
                        if (!finishReasons.isEmpty()) {
                            finishReason = finishReasonCategory(finishReasons.getFirst());
                            span.setAttribute(AttributeKey.stringArrayKey(
                                    "gen_ai.response.finish_reasons"), finishReasons);
                            if (agentInvocation != null) {
                                agentInvocation.recordFinishReasons(sequence, finishReasons);
                            } else {
                                turn.recordFinishReasons(sequence, finishReasons);
                            }
                        }
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
            Attributes customAttributes = Attributes.builder()
                    .putAll(providerModelAttributes(provider, model, null))
                    .put("score.ai.token.type", type)
                    .build();
            instruments.tokens.add(count.longValue(), customAttributes);
            Attributes standardAttributes = Attributes.builder()
                    .putAll(GenAiSemanticConventions.inferenceAttributes(
                            GenAiSemanticConventions.CHAT, provider, model, responseModel, null))
                    .put("gen_ai.token.type", type)
                    .build();
            instruments.genAiClientTokenUsage.record(count.longValue(), standardAttributes);
            span.setAttribute(spanAttribute, count.longValue());
            AtomicLong aggregate = "input".equals(type)
                    ? agentInvocation != null ? agentInvocation.inputTokens : turn.rootInputTokens
                    : agentInvocation != null ? agentInvocation.outputTokens : turn.rootOutputTokens;
            aggregate.addAndGet(count.longValue());
        }

        private void cacheToken(String type, Number count, String spanAttribute) {
            if (count == null || count.longValue() < 0) return;
            instruments.tokens.add(count.longValue(), Attributes.builder()
                    .putAll(providerModelAttributes(provider, model, null))
                    .put("score.ai.token.type", type).build());
            span.setAttribute(spanAttribute, count.longValue());
            boolean cacheRead = "cache_read".equals(type);
            AtomicLong aggregate = agentInvocation != null
                    ? (cacheRead ? agentInvocation.cacheReadTokens
                            : agentInvocation.cacheCreationTokens)
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
                span.setAttribute("error.type", outcome);
                span.setStatus(StatusCode.ERROR, outcome);
            }
            span.setAttribute("score.ai.outcome", outcome);
            if (incomplete) span.setAttribute("score.ai.observation.incomplete", true);
            double duration = elapsedMillis(startedNanos);
            Attributes attributes = Attributes.builder()
                    .putAll(providerModelAttributes(provider, model, outcome))
                    .put("score.ai.response.finish_reason", finishReasonCategory(finishReason))
                    .build();
            instruments.modelCalls.add(1, attributes);
            instruments.modelDuration.record(duration, attributes);
            instruments.genAiClientOperationDuration.record(
                    GenAiSemanticConventions.elapsedSeconds(startedNanos),
                    GenAiSemanticConventions.inferenceAttributes(
                            GenAiSemanticConventions.CHAT, provider, model, responseModel,
                            failure != null ? failure.getClass().getName()
                                    : !"success".equals(outcome) ? outcome : null));
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

    private static String finishReasonValue(String reason) {
        String normalized = reason != null ? reason.strip() : "";
        return normalized.matches("[A-Za-z0-9_.:/-]{1,80}") ? normalized : "_OTHER";
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
