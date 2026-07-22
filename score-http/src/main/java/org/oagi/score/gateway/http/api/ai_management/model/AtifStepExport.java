package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.List;
import java.util.Map;

/**
 * Normalized step export and the measured portion of its aggregate usage.
 *
 * <p>Token totals remain partial until the matching measurement count covers every
 * tracked LLM call. Keeping the coverage counts beside the totals prevents an absent
 * provider measurement from being silently interpreted as zero.</p>
 */
public record AtifStepExport(
        List<Map<String, Object>> steps,
        AtifMetricAggregate promptTokens,
        AtifMetricAggregate completionTokens,
        AtifMetricAggregate cachedTokens,
        int trackedLlmCalls,
        int untrackedLlmSteps,
        int collapsedUiProjections) {

    public boolean promptTokensComplete() {
        return promptTokens.complete(trackedLlmCalls, untrackedLlmSteps);
    }

    public boolean completionTokensComplete() {
        return completionTokens.complete(trackedLlmCalls, untrackedLlmSteps);
    }

    public boolean cachedTokensComplete() {
        return cachedTokens.complete(trackedLlmCalls, untrackedLlmSteps);
    }

    public boolean metricsComplete() {
        return promptTokensComplete() && completionTokensComplete() && cachedTokensComplete();
    }
}
