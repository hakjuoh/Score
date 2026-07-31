package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns activation and terminal projection for one planning operation span. */
final class AiPlanOperationTelemetry implements ExecutionObservationContext.Operation {

    private final AiTurnState turn;
    private final Span span;
    private final Context context;
    private final AiObservationEvents events;
    private final AtomicBoolean ended = new AtomicBoolean();
    private Scope scope = Scope.noop();
    private volatile Throwable failure;
    private volatile boolean cancelled;

    AiPlanOperationTelemetry(AiTurnState turn, Span span, Context context,
                             AiObservationEvents events) {
        this.turn = turn;
        this.span = span;
        this.context = context;
        this.events = events;
    }

    void activate() {
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

    void closeFromTurn(String outcome) {
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
        var terminal = events.publish("plan.completed",
                events.scope(turn.requestId, turn.conversationId, null, turn.generation,
                        ExecutionScope.Purpose.WORKFLOW_PLANNING), Map.of(
                        "outcome", normalized,
                        "failure_type", errorType != null ? errorType : "none"));
        AiObservationEvents.terminalIdentity(span, terminal);
        synchronized (turn) {
            turn.activeOperations.remove(this);
        }
        span.end();
    }
}
