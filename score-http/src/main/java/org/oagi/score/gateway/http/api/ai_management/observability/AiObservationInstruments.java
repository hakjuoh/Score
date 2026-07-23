package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;

/** AI-specific metric instruments. Callers supply only bounded-cardinality attributes. */
final class AiObservationInstruments {

    final LongCounter turns;
    final DoubleHistogram turnDuration;
    final DoubleHistogram timeToFirstToken;
    final LongUpDownCounter activeRequests;
    final DoubleHistogram queueDelay;
    final LongCounter admissionRejections;
    final LongCounter modelCalls;
    final DoubleHistogram modelDuration;
    final LongCounter tokens;
    final LongCounter providerRetries;
    final DoubleHistogram providerRetryDelay;
    final LongCounter toolCalls;
    final DoubleHistogram toolDuration;
    final LongCounter toolTruncations;
    final LongCounter agentRuns;
    final DoubleHistogram agentDuration;
    final LongCounter workflows;
    final DoubleHistogram workflowDuration;
    final LongCounter workflowFanout;
    final LongCounter workflowIterations;
    final LongCounter guardrailDecisions;
    final DoubleHistogram approvalWait;
    final DoubleHistogram contextWindowUsage;
    final LongCounter compactions;
    final DoubleHistogram cost;

    AiObservationInstruments(Meter meter) {
        turns = counter(meter, "score.ai.turn.requests", "AI turns completed by outcome");
        turnDuration = duration(meter, "score.ai.turn.duration", "AI turn duration");
        timeToFirstToken = duration(meter, "score.ai.turn.time_to_first_token",
                "Time from admission to the first model response chunk");
        activeRequests = meter.upDownCounterBuilder("score.ai.requests.active")
                .setDescription("Active AI requests").setUnit("{request}").build();
        queueDelay = duration(meter, "score.ai.queue.delay", "AI executor queue delay");
        admissionRejections = counter(meter, "score.ai.admission.rejections",
                "AI requests rejected before execution");
        modelCalls = counter(meter, "score.ai.model.calls", "Model calls");
        modelDuration = duration(meter, "score.ai.model.duration", "Model call duration");
        tokens = counter(meter, "score.ai.model.tokens", "Model tokens");
        providerRetries = counter(meter, "score.ai.model.retries", "Provider retries");
        providerRetryDelay = duration(meter, "score.ai.model.retry_delay", "Provider retry delay");
        toolCalls = counter(meter, "score.ai.tool.calls", "Tool calls");
        toolDuration = duration(meter, "score.ai.tool.duration", "Tool call duration");
        toolTruncations = counter(meter, "score.ai.tool.truncations", "Truncated tool results");
        agentRuns = counter(meter, "score.ai.agent.runs", "Agent runs");
        agentDuration = duration(meter, "score.ai.agent.duration", "Agent run duration");
        workflows = counter(meter, "score.ai.workflow.runs", "Workflow runs");
        workflowDuration = duration(meter, "score.ai.workflow.duration", "Workflow duration");
        workflowFanout = counter(meter, "score.ai.workflow.fanout", "Planned workflow workers");
        workflowIterations = counter(meter, "score.ai.workflow.iterations", "Workflow iterations");
        guardrailDecisions = counter(meter, "score.ai.guardrail.decisions", "Guardrail decisions");
        approvalWait = duration(meter, "score.ai.approval.wait", "Approval wait duration");
        contextWindowUsage = meter.histogramBuilder("score.ai.context_window.usage")
                .setDescription("Context-window utilization").setUnit("%").build();
        compactions = counter(meter, "score.ai.context.compactions", "Context compactions");
        cost = meter.histogramBuilder("score.ai.cost")
                .setDescription("Provider-reported or pricing-engine AI cost")
                .setUnit("USD").build();
    }

    private static LongCounter counter(Meter meter, String name, String description) {
        return meter.counterBuilder(name).setDescription(description).setUnit("{event}").build();
    }

    private static DoubleHistogram duration(Meter meter, String name, String description) {
        return meter.histogramBuilder(name).setDescription(description).setUnit("ms").build();
    }

    static String normalized(String value) {
        return value != null && !value.isBlank() ? value.strip().toLowerCase() : "unknown";
    }
}
