package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiObservabilityConfiguration.ScoreAiObservabilitySdk;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AiRequestModelResolver requestModels;
    private final AiTurnRegistry turnRegistry;
    private final AiObservationEvents events;
    private final AiAdmissionObservation admissionObservation;
    private final AiObservationStarts starts;

    @Autowired
    public ScoreAiObservability(ScoreAiObservabilitySdk sdk, ScoreAiModelRegistry models,
                                ObjectProvider<ExecutionObserver> eventPublishers) {
        this(sdk.openTelemetry(), sdk.serviceVersion(), alias ->
                models.modelConfiguration(alias).model(), eventPublishers);
    }

    ScoreAiObservability(OpenTelemetry openTelemetry, String serviceVersion) {
        this(openTelemetry, serviceVersion, Function.identity());
    }

    ScoreAiObservability(OpenTelemetry openTelemetry, String serviceVersion,
                         Function<String, String> requestModelResolver) {
        this(openTelemetry, serviceVersion, requestModelResolver, null);
    }

    ScoreAiObservability(OpenTelemetry openTelemetry, String serviceVersion,
                         Function<String, String> requestModelResolver,
                         ObjectProvider<ExecutionObserver> eventPublishers) {
        OpenTelemetry installed = Objects.requireNonNull(openTelemetry, "openTelemetry");
        this.serviceVersion = StringUtils.hasText(serviceVersion) ? serviceVersion : "unknown";
        this.tracer = installed.getTracer(INSTRUMENTATION_SCOPE);
        this.instruments = new AiObservationInstruments(installed.getMeter(INSTRUMENTATION_SCOPE));
        this.requestModels = new AiRequestModelResolver(requestModelResolver);
        this.events = new AiObservationEvents(eventPublishers);
        this.admissionObservation = new AiAdmissionObservation(tracer, instruments, events);
        this.turnRegistry = new AiTurnRegistry(tracer, instruments, events);
        this.starts = new AiObservationStarts(
                tracer, instruments, events, turnRegistry, requestModels);
    }

    public static ScoreAiObservability noop() {
        return new ScoreAiObservability(OpenTelemetry.noop(), "unknown");
    }

    /**
     * Starts the turn's entrypoint span. A valid inbound W3C trace context is continued when
     * supplied.
     *
     * <p>The entrypoint is an {@code invoke_workflow}, not an {@code invoke_agent}: it groups the
     * agent invocations a turn makes rather than being one of them, which is exactly the case the
     * GenAI conventions reserve {@code invoke_workflow} for. Its {@code gen_ai.workflow.name} is
     * the execution kind ({@code assistant}, {@code context_compaction}, ...), a low-cardinality
     * label that names how the turn was entered.</p>
     */
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
        return new Turn(starts.startExecution(
                execution, requester, generation, extractedParent(traceparent, tracestate)));
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
                traceparent, tracestate, 0L);
    }

    public void recordAdmissionRejection(ChatRequest request, ScoreUser requester,
                                         Throwable failure, String reason,
                                         String traceparent, String tracestate) {
        recordAdmissionRejection(request, requester, failure, reason, traceparent, tracestate, 0L);
    }

    public void recordAdmissionRejection(ChatRequest request, ScoreUser requester,
                                         Throwable failure, String reason,
                                         String traceparent, String tracestate,
                                         long generation) {
        if (request == null) return;
        long startedNanos = System.nanoTime();
        String requestModel = requestModels.resolve(request.modelName());
        admissionObservation.record(request, requester, failure, reason, generation,
                requestModel, startedNanos,
                () -> extractedParent(traceparent, tracestate));
    }

    public ModelCall startModelCall(String requestId, String model, String provider, String phase) {
        return startModelCall(requestId, model, model, provider, phase);
    }

    public ModelCall startModelCall(String requestId, String modelAlias, String requestModel,
                                    String provider, String phase) {
        return startModelCall(requestId, modelAlias, requestModel, provider, phase, null, null);
    }

    public ModelCall startModelCall(String requestId, String modelAlias, String requestModel,
                                    String provider, String phase, String parentOperationId,
                                    String conversationId) {
        return startModelCall(requestId, modelAlias, requestModel, provider, phase,
                parentOperationId, conversationId, null);
    }

    public ModelCall startModelCall(String requestId, String modelAlias, String requestModel,
                                    String provider, String phase, String parentOperationId,
                                    String conversationId,
                                    AiTrajectoryRecorder.ExecutionEventIdentity started) {
        return new ModelCall(starts.startModelCall(
                requestId, modelAlias, requestModel, provider, phase,
                parentOperationId, conversationId, started));
    }

    private ExecutionScope scope(String requestId, String conversationId, ScoreUser requester,
                                 long generation, ExecutionScope.Purpose purpose) {
        return events.scope(requestId, conversationId, requester, generation, purpose);
    }

    private ExecutionObservation publish(String type, ExecutionScope scope,
                                         Map<String, Object> attributes) {
        return publish(type, scope, attributes, ignored -> { });
    }

    private ExecutionObservation publish(String type, ExecutionScope scope,
                                         Map<String, Object> attributes,
                                         java.util.function.Consumer<ExecutionObservation> projection) {
        return events.publish(type, scope, attributes, projection);
    }

    /** Consumes content-minimized lifecycle facts emitted through {@code ExecutionObserver}. */
    void observe(String requestId, AiExecutionLifecycle event) {
        turnRegistry.observe(requestId, event);
    }

    public void recordGuardrails(String requestId, String scope,
                                 List<GuardrailDecision> decisions) {
        recordGuardrails(requestId, scope, decisions, null);
    }

    public void recordGuardrails(String requestId, String scope,
                                 List<GuardrailDecision> decisions,
                                 GuardrailRefusal refusal) {
        turnRegistry.whileActive(requestId,
                () -> recordActiveGuardrails(requestId, scope, decisions, refusal));
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
            AiTurnState turn = turnRegistry.turns.get(requestId);
            Attributes labels = Attributes.builder()
                    .put("score.ai.guardrail.scope", value(scope).toLowerCase())
                    .put("score.ai.guardrail.action", action)
                    .build();
            ExecutionObservation published = null;
            if (turn != null) {
                published = publish("guardrail.decision",
                        scope(requestId, turn.conversationId, null, turn.generation,
                                ExecutionScope.Purpose.GUARDRAIL_EVALUATION), Map.of(
                                "action", action,
                                "decision_id", decision.decisionId(),
                                "policy_id", decision.policyId()), event -> {
                            AttributesBuilder eventAttributes = Attributes.builder().putAll(labels)
                                    .put("score.ai.guardrail.decision_id", decision.decisionId())
                                    .put("score.ai.guardrail.policy_id", decision.policyId())
                                    .put("score.ai.guardrail.policy_version", decision.policyVersion())
                                    .put(ExecutionEventPublisher.EVENT_ID,
                                            event.attributes().get(
                                                    ExecutionEventPublisher.EVENT_ID).toString())
                                    .put(ExecutionEventPublisher.EVENT_SEQUENCE,
                                            ((Number) event.attributes().get(
                                                    ExecutionEventPublisher.EVENT_SEQUENCE)).longValue());
                            instruments.guardrailDecisions.add(1, labels);
                            Span.fromContext(turnRegistry.parentContext(requestId)).addEvent(
                                    "score.ai.guardrail.decision", eventAttributes.build());
                        });
            }
            if (published == null) {
                instruments.guardrailDecisions.add(1, labels);
                Span.fromContext(turnRegistry.parentContext(requestId)).addEvent(
                        "score.ai.guardrail.decision", Attributes.builder().putAll(labels)
                                .put("score.ai.guardrail.decision_id", decision.decisionId())
                                .put("score.ai.guardrail.policy_id", decision.policyId())
                                .put("score.ai.guardrail.policy_version", decision.policyVersion())
                                .build());
            }
        }
    }

    /** Correlation fields persisted in ATIF extra_json; empty when observability is disabled. */
    public Map<String, Object> correlation(String requestId) {
        if (requestId == null) return Map.of();
        AiTurnState turn = turnRegistry.turns.get(requestId);
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
        return turnRegistry.parentContext(requestId);
    }

    static Context privateContext(Context parent, Span span) {
        return parent.with(span).with(PRIVATE_AI_CONTEXT, true);
    }

    static boolean isPrivateContext(Context context) {
        return Boolean.TRUE.equals(context.get(PRIVATE_AI_CONTEXT));
    }

    static Context privateContext(Context parent, Span span, AtomicBoolean ended) {
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
        return turnRegistry.makeAgentCurrent(requestId, runId);
    }

    @Override
    public ExecutionObservationContext.Activation makeToolCurrent(String requestId, String toolCallId) {
        return turnRegistry.makeToolCurrent(requestId, toolCallId);
    }

    @Override
    public ExecutionObservationContext.Operation startPlan(String requestId, String agentName) {
        return turnRegistry.startPlan(requestId, agentName);
    }

    void registerAgentContext(String requestId, String runId, String agentName,
                              String workflowNodeId, Context context) {
        turnRegistry.registerAgentContext(
                requestId, runId, agentName, workflowNodeId, context);
    }

    AgentInvocationCounts removeAgentContext(String requestId, String runId) {
        return turnRegistry.removeAgentContext(requestId, runId);
    }

    record AgentInvocationCounts(long inferenceCalls, long toolCalls,
                                 AgentUsage usage, List<String> finishReasons) {
        static final AgentInvocationCounts EMPTY =
                new AgentInvocationCounts(0, 0, AgentUsage.EMPTY, List.of());
    }

    record AgentUsage(long inputTokens, long outputTokens,
                      long cacheReadTokens, boolean cacheReadObserved,
                      long cacheCreationTokens, boolean cacheCreationObserved) {
        private static final AgentUsage EMPTY = new AgentUsage(0, 0, 0, false, 0, false);
    }

    boolean whileActive(String requestId, Runnable action) {
        return turnRegistry.whileActive(requestId, action);
    }

    /** Allows a listener for an already-sequenced event to drain while terminal close waits. */
    boolean whileCausallyActive(String requestId, Runnable action) {
        return turnRegistry.whileCausallyActive(requestId, action);
    }

    Context agentParent(String requestId, String workflowNodeId, String workflowParentNodeId) {
        return turnRegistry.agentParent(requestId, workflowNodeId, workflowParentNodeId);
    }

    void onTurnClosed(BiConsumer<String, String> listener) {
        turnRegistry.onTurnClosed(listener);
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

    static Attributes providerModelAttributes(String provider, String model, String outcome) {
        return AiObservationLabels.providerModel(provider, model, outcome);
    }

    static String value(String value) {
        return AiObservationLabels.value(value);
    }

    public final class Turn {
        private final AiTurnTelemetry telemetry;

        private Turn(AiTurnTelemetry telemetry) {
            this.telemetry = telemetry;
        }

        public void executionStarted() {
            if (telemetry != null) telemetry.executionStarted();
        }

        public void prepared(ChatRequest request) {
            if (telemetry != null) telemetry.prepared(request);
        }

        public void admissionRejected(String reason) {
            if (telemetry != null) telemetry.admissionRejected(reason);
        }

        public void complete(String outcome, Throwable failure) {
            if (telemetry != null) telemetry.complete(outcome, failure);
        }
    }

    public final class ModelCall {
        private final AiModelCallTelemetry telemetry;

        private ModelCall(AiModelCallTelemetry telemetry) {
            this.telemetry = telemetry;
        }

        public void streaming() {
            telemetry.streaming();
        }

        public void firstChunk() {
            telemetry.firstChunk();
        }

        public void firstToken() {
            telemetry.firstToken();
        }

        public void eventIdentity(AiTrajectoryRecorder.ExecutionEventIdentity event) {
            telemetry.eventIdentity(event);
        }

        public void complete(ChatResponse response) {
            telemetry.complete(response);
        }

        public void fail(Throwable failure) {
            telemetry.fail(failure);
        }

        public void cancel() {
            telemetry.cancel();
        }

    }

}
