package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
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
    final LongCounter quotaReservedTokens;
    final LongCounter quotaConsumedTokens;
    final LongCounter quotaReleasedTokens;
    final LongCounter quotaOverageTokens;
    final LongCounter quotaReconciliationCount;
    final LongCounter policyDenied;
    final LongCounter multiAgentPolicyDowngrade;
    final DoubleHistogram specialistAdmissionWait;
    final LongCounter specialistAdmissionRejected;
    final LongCounter catalogCacheRefresh;
    final LongCounter providerSecretDecryptionFailed;

    // OpenTelemetry GenAI semantic-convention instruments. Custom SCORE metrics above remain
    // available for operational continuity while dashboards migrate to these standard names.
    final LongHistogram genAiClientTokenUsage;
    final DoubleHistogram genAiClientOperationDuration;
    final DoubleHistogram genAiClientTimeToFirstChunk;
    final DoubleHistogram genAiWorkflowDuration;
    final DoubleHistogram genAiInvokeAgentDuration;
    final LongHistogram genAiInvokeAgentInferenceCalls;
    final LongHistogram genAiInvokeAgentToolCalls;
    final DoubleHistogram genAiExecuteToolDuration;

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
        quotaReservedTokens = tokenCounter(meter, "ai.quota.reserved_tokens",
                "AI quota tokens reserved before provider calls");
        quotaConsumedTokens = tokenCounter(meter, "ai.quota.consumed_tokens",
                "AI quota tokens charged after provider calls");
        quotaReleasedTokens = tokenCounter(meter, "ai.quota.released_tokens",
                "AI quota reservations released without provider usage");
        quotaOverageTokens = tokenCounter(meter, "ai.quota.overage_tokens",
                "Provider usage exceeding its reservation");
        quotaReconciliationCount = counter(meter, "ai.quota.reconciliation_count",
                "Abandoned AI quota reservations reconciled");
        policyDenied = counter(meter, "ai.policy.denied",
                "AI requests denied by a user policy");
        multiAgentPolicyDowngrade = counter(meter, "ai.multi_agent.policy_downgrade",
                "Multi-agent requests downgraded by policy");
        specialistAdmissionWait = duration(meter, "ai.specialist.admission_wait",
                "Time waiting for a specialist execution permit");
        specialistAdmissionRejected = counter(meter, "ai.specialist.admission_rejected",
                "Specialist permit waits terminated before admission");
        catalogCacheRefresh = counter(meter, "ai.catalog.cache_refresh",
                "Runtime AI catalog cache refreshes");
        providerSecretDecryptionFailed = counter(meter,
                "ai.provider.secret_decryption_failed",
                "AI provider secret decryption failures");

        genAiClientTokenUsage = meter.histogramBuilder("gen_ai.client.token.usage")
                .setDescription("Number of input and output tokens used")
                .setUnit("{token}")
                .ofLongs()
                .setExplicitBucketBoundariesAdvice(GenAiSemanticConventions.TOKEN_BUCKETS)
                .build();
        genAiClientOperationDuration = standardDuration(meter,
                "gen_ai.client.operation.duration", "GenAI client operation duration");
        genAiClientTimeToFirstChunk = standardDuration(meter,
                "gen_ai.client.operation.time_to_first_chunk",
                "Time to receive the first chunk of a streaming GenAI response");
        genAiWorkflowDuration = meter.histogramBuilder("gen_ai.workflow.duration")
                .setDescription("GenAI workflow duration").setUnit("s")
                .setExplicitBucketBoundariesAdvice(
                        GenAiSemanticConventions.WORKFLOW_DURATION_BUCKETS_SECONDS)
                .build();
        genAiInvokeAgentDuration = standardDuration(meter,
                "gen_ai.invoke_agent.duration", "GenAI agent invocation duration");
        genAiInvokeAgentInferenceCalls = callHistogram(meter,
                "gen_ai.invoke_agent.inference_calls", "Inference calls per agent invocation",
                "{inference_call}");
        genAiInvokeAgentToolCalls = callHistogram(meter,
                "gen_ai.invoke_agent.tool_calls", "Tool calls per agent invocation",
                "{tool_call}");
        genAiExecuteToolDuration = standardDuration(meter,
                "gen_ai.execute_tool.duration", "GenAI tool execution duration");
    }

    private static LongCounter counter(Meter meter, String name, String description) {
        return meter.counterBuilder(name).setDescription(description).setUnit("{event}").build();
    }

    private static LongCounter tokenCounter(Meter meter, String name, String description) {
        return meter.counterBuilder(name).setDescription(description).setUnit("{token}").build();
    }

    private static DoubleHistogram duration(Meter meter, String name, String description) {
        return meter.histogramBuilder(name).setDescription(description).setUnit("ms").build();
    }

    private static DoubleHistogram standardDuration(Meter meter, String name, String description) {
        return meter.histogramBuilder(name).setDescription(description).setUnit("s")
                .setExplicitBucketBoundariesAdvice(GenAiSemanticConventions.DURATION_BUCKETS_SECONDS)
                .build();
    }

    private static LongHistogram callHistogram(Meter meter, String name, String description,
                                               String unit) {
        return meter.histogramBuilder(name).setDescription(description).setUnit(unit)
                .ofLongs()
                .setExplicitBucketBoundariesAdvice(GenAiSemanticConventions.CALL_BUCKETS)
                .build();
    }

    static String normalized(String value) {
        return value != null && !value.isBlank() ? value.strip().toLowerCase() : "unknown";
    }
}
