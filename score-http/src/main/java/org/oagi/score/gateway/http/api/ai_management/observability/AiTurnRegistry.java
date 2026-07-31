package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Owns active-turn lookup, context attribution, and publication admission. */
final class AiTurnRegistry {

    final ConcurrentMap<String, AiTurnState> turns = new ConcurrentHashMap<>();
    final List<BiConsumer<String, String>> closeListeners = new CopyOnWriteArrayList<>();
    final AiLifecycleEventObserver lifecycleEvents;
    private final Tracer tracer;
    private final AiObservationEvents events;

    AiTurnRegistry(Tracer tracer, AiObservationInstruments instruments,
                   AiObservationEvents events) {
        this.tracer = tracer;
        this.events = events;
        this.lifecycleEvents = new AiLifecycleEventObserver(
                tracer, instruments, this::parentContext, this::activeAgentName,
                this::explicitParentContext);
    }

    void observe(String requestId, AiExecutionLifecycle event) {
        AiTurnState turn = requestId != null ? turns.get(requestId) : null;
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

    Context parentContext(String requestId) {
        if (requestId == null) return Context.root();
        AiTurnState state = turns.get(requestId);
        if (state == null || state.ended.get()) return Context.root();
        Context current = Context.current();
        var currentSpan = Span.fromContext(current).getSpanContext();
        if (ScoreAiObservability.isPrivateContext(current) && currentSpan.isValid()
                && currentSpan.getTraceId().equals(state.span.getSpanContext().getTraceId())) {
            return current;
        }
        return state.agentContexts.size() == 1
                ? state.agentContexts.values().iterator().next() : state.context;
    }

    Scope makeAgentCurrent(String requestId, String runId) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null) return Scope.noop();
        synchronized (state) {
            if (state.ended.get() || state.closing.get()) return Scope.noop();
            Context context = state.agentContexts.get(runId);
            return context != null ? context.makeCurrent() : Scope.noop();
        }
    }

    ExecutionObservationContext.Activation makeToolCurrent(String requestId, String toolCallId) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || toolCallId == null) return () -> { };
        synchronized (state) {
            if (state.ended.get() || state.closing.get()) return () -> { };
            Context context = lifecycleEvents.toolContext(requestId, toolCallId);
            Scope scope = context != null ? context.makeCurrent() : Scope.noop();
            return scope::close;
        }
    }

    ExecutionObservationContext.Operation startPlan(String requestId, String agentName) {
        return withActiveTurn(requestId, ExecutionObservationContext.Operation.noop(), state -> {
            String target = AiObservationLabels.value(agentName);
            Context parent = parentContext(requestId);
            var startEvent = events.publish("plan.started",
                    events.scope(requestId, state.conversationId, null, state.generation,
                            ExecutionScope.Purpose.WORKFLOW_PLANNING),
                    Map.of("agent_id", target));
            SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                            GenAiSemanticConventions.PLAN, target))
                    .setParent(parent).setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.PLAN);
            AiObservationEvents.startIdentity(builder, startEvent);
            GenAiSemanticConventions.putIfKnown(builder, "gen_ai.agent.name", target);
            Span span = builder.startSpan();
            AiPlanOperationTelemetry observed = new AiPlanOperationTelemetry(
                    state, span, ScoreAiObservability.privateContext(parent, span), events);
            synchronized (state) {
                state.activeOperations.add(observed);
            }
            observed.activate();
            return observed;
        });
    }

    void registerAgentContext(String requestId, String runId, String agentName,
                              String workflowNodeId, Context context) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null || context == null) return;
        synchronized (state) {
            if (!state.ended.get() && turns.get(requestId) == state) {
                Context attributed = context.with(AiTurnState.ACTIVE_AGENT_RUN_ID, runId);
                state.agentContexts.put(runId, attributed);
                state.agentInvocations.put(runId,
                        new AiTurnState.AgentInvocation(agentName, workflowNodeId, attributed));
                if (StringUtils.hasText(workflowNodeId)) {
                    state.agentNodeContexts.put(workflowNodeId, attributed);
                }
            }
        }
    }

    ScoreAiObservability.AgentInvocationCounts removeAgentContext(
            String requestId, String runId) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || runId == null) return ScoreAiObservability.AgentInvocationCounts.EMPTY;
        synchronized (state) {
            state.agentContexts.remove(runId);
            AiTurnState.AgentInvocation invocation = state.agentInvocations.remove(runId);
            if (invocation != null && StringUtils.hasText(invocation.workflowNodeId)) {
                state.agentNodeContexts.remove(invocation.workflowNodeId, invocation.context);
            }
            return invocation != null
                    ? invocation.snapshot() : ScoreAiObservability.AgentInvocationCounts.EMPTY;
        }
    }

    boolean whileActive(String requestId, Runnable action) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || !state.reservePublication()) return false;
        try {
            action.run();
            return true;
        } finally {
            state.releasePublication();
        }
    }

    boolean whileCausallyActive(String requestId, Runnable action) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null) return false;
        synchronized (state) {
            if (state.ended.get() || turns.get(requestId) != state) return false;
        }
        action.run();
        return true;
    }

    <T> T withActiveTurn(String requestId, T inactive, Function<AiTurnState, T> action) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || !state.reservePublication()) return inactive;
        try {
            return action.apply(state);
        } finally {
            state.releasePublication();
        }
    }

    Context agentParent(String requestId, String workflowNodeId, String parentNodeId) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || state.ended.get()) return Context.root();
        Context workflow = lifecycleEvents.workflowContext(requestId, workflowNodeId);
        if (workflow != null) return workflow;
        workflow = lifecycleEvents.workflowContext(requestId, parentNodeId);
        return workflow != null ? workflow : state.context;
    }

    Context explicitParentContext(String requestId, String operationId) {
        if (!StringUtils.hasText(requestId) || !StringUtils.hasText(operationId)) return null;
        Context workflow = lifecycleEvents.workflowContext(requestId, operationId);
        if (workflow != null) return workflow;
        AiTurnState state = turns.get(requestId);
        if (state == null || state.ended.get()) return null;
        Context agent = state.agentNodeContexts.get(operationId);
        if (agent != null) return agent;
        AiTurnState.AgentInvocation invocation = state.agentInvocations.get(operationId);
        return invocation != null ? invocation.context : null;
    }

    void onTurnClosed(BiConsumer<String, String> listener) {
        closeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private String activeAgentName(String requestId) {
        AiTurnState state = requestId != null ? turns.get(requestId) : null;
        if (state == null || state.ended.get()) return null;
        Context current = Context.current();
        String activeRunId = current.get(AiTurnState.ACTIVE_AGENT_RUN_ID);
        AiTurnState.AgentInvocation attributed = activeRunId != null
                ? state.agentInvocations.get(activeRunId) : null;
        if (attributed != null) return attributed.agentName;
        String currentSpanId = Span.fromContext(current).getSpanContext().getSpanId();
        return state.agentInvocations.values().stream()
                .filter(invocation -> Span.fromContext(invocation.context).getSpanContext()
                        .getSpanId().equals(currentSpanId))
                .map(invocation -> invocation.agentName).findFirst()
                .orElseGet(() -> state.agentInvocations.size() == 1
                        ? state.agentInvocations.values().iterator().next().agentName : null);
    }
}
