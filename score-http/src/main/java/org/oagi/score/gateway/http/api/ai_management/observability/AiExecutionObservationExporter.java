package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exports content-free Agent and Guardrail lifecycle facts from the execution boundary. */
@Component
final class AiExecutionObservationExporter implements ExecutionObserver {

    private final ScoreAiObservability observability;
    private final AiLifecycleOperationRegistry<RunKey, AgentRun> runs =
            new AiLifecycleOperationRegistry<>();

    AiExecutionObservationExporter(ScoreAiObservability observability) {
        this.observability = observability;
        observability.onTurnClosed(this::closeRequest);
    }

    @Override
    public void observe(ExecutionObservation observation) {
        if (AiExecutionLifecycle.OBSERVATION_TYPE.equals(observation.type())) {
            AiExecutionLifecycle.from(observation).ifPresent(event ->
                    observability.observe(observation.scope().requestId(), event));
        } else if (observation.type().startsWith("agent.run.")) {
            observability.whileActive(observation.scope().requestId(),
                    () -> observeAgent(observation));
        } else if (observation.type().equals("guardrail.tool.decision")) {
            observability.whileActive(observation.scope().requestId(),
                    () -> observeGuardrail(observation));
        }
    }

    private void observeAgent(ExecutionObservation observation) {
        Map<String, Object> attributes = observation.attributes();
        String requestId = observation.scope().requestId();
        String runId = ScoreAiObservability.value(Objects.toString(attributes.get("agent_run_id"), null));
        String agent = ScoreAiObservability.value(Objects.toString(attributes.get("agent_id"), null));
        String model = ScoreAiObservability.value(Objects.toString(attributes.get("model_id"), null));
        String workflowNodeId = ScoreAiObservability.value(
                Objects.toString(attributes.get("workflow_node_id"), null));
        RunKey key = new RunKey(requestId, runId);
        if (observation.type().endsWith(".started")) {
            runs.start(key, () -> {
                var parent = observability.agentParent(requestId, workflowNodeId);
                Span span = observability.tracer().spanBuilder("score.ai.agent")
                            .setParent(parent)
                            .setAttribute("gen_ai.agent.id", agent)
                            .setAttribute("gen_ai.request.model", model)
                            .setAttribute("score.ai.agent_run.id", runId)
                            .setAttribute("score.ai.workflow.node_id", workflowNodeId)
                            .setAttribute("score.ai.execution.purpose",
                                    observation.scope().purpose().name().toLowerCase())
                            .startSpan();
                Context context = ScoreAiObservability.privateContext(parent, span);
                observability.registerAgentContext(requestId, runId, context);
                return new AgentRun(span, System.nanoTime(), model, requestId, runId);
            });
            return;
        }
        AgentRun run = runs.terminate(key, () -> {
            var parent = observability.agentParent(requestId, workflowNodeId);
            Span span = observability.tracer().spanBuilder("score.ai.agent")
                    .setParent(parent)
                    .setAttribute("gen_ai.agent.id", agent)
                    .setAttribute("gen_ai.request.model", model)
                    .setAttribute("score.ai.agent_run.id", runId)
                    .setAttribute("score.ai.workflow.node_id", workflowNodeId)
                    .startSpan();
            return new AgentRun(span, System.nanoTime(), model, requestId, runId);
        });
        if (run == null) return;
        String outcome = observation.type().endsWith(".completed") ? "success"
                : observation.type().endsWith(".cancelled") ? "cancelled"
                : observation.type().endsWith(".timed_out") ? "timeout" : "error";
        Object failure = attributes.get("failure_type");
        if (failure != null) run.span.setAttribute("error.type", failure.toString());
        run.finish(outcome);
    }

    private void closeRequest(String requestId, String outcome) {
        runs.closeMatching(key -> key.requestId.equals(requestId),
                run -> run.finish(outcome, true));
    }

    private void observeGuardrail(ExecutionObservation observation) {
        Map<String, Object> source = observation.attributes();
        String action = ScoreAiObservability.value(Objects.toString(source.get("action"), null)).toLowerCase();
        String tool = ScoreAiObservability.value(Objects.toString(source.get("tool_id"), null));
        Attributes labels = Attributes.builder()
                .put("score.ai.guardrail.scope", "tool")
                .put("score.ai.guardrail.action", action)
                .build();
        observability.instruments().guardrailDecisions.add(1, labels);
        Span.fromContext(observability.parentContext(observation.scope().requestId()))
                .addEvent("score.ai.guardrail.decision", Attributes.builder()
                        .putAll(labels)
                        .put("score.ai.guardrail.decision_id",
                                ScoreAiObservability.value(Objects.toString(source.get("decision_id"), null)))
                        .put("score.ai.guardrail.policy_id",
                                ScoreAiObservability.value(Objects.toString(source.get("policy_id"), null)))
                        .put("gen_ai.tool.name", tool)
                        .build());
    }

    private static double elapsedMillis(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toNanos()
                / 1_000_000.0;
    }

    private record RunKey(String requestId, String runId) { }

    private final class AgentRun {
        private final Span span;
        private final long startedNanos;
        private final String model;
        private final String requestId;
        private final String runId;
        private final AtomicBoolean ended = new AtomicBoolean();

        private AgentRun(Span span, long startedNanos, String model,
                         String requestId, String runId) {
            this.span = span;
            this.startedNanos = startedNanos;
            this.model = model;
            this.requestId = requestId;
            this.runId = runId;
        }

        private void finish(String outcome) {
            finish(outcome, false);
        }

        private void finish(String outcome, boolean incomplete) {
            if (!ended.compareAndSet(false, true)) return;
            observability.removeAgentContext(requestId, runId);
            span.setAttribute("score.ai.outcome", outcome);
            if (incomplete) span.setAttribute("score.ai.observation.incomplete", true);
            if (!"success".equals(outcome) && !"cancelled".equals(outcome)) {
                span.setStatus(StatusCode.ERROR, outcome);
            }
            Attributes labels = Attributes.builder()
                    .put("gen_ai.request.model", model)
                    .put("score.ai.outcome", outcome)
                    .build();
            observability.instruments().agentRuns.add(1, labels);
            observability.instruments().agentDuration.record(elapsedMillis(startedNanos), labels);
            span.end();
        }
    }
}
