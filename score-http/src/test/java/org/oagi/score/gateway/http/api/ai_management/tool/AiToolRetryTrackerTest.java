package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiObservationAccumulator;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStepId;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingTool;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolRetryTrackerTest {

    @Test
    void keepsSameRoundCallsIndependentAndPairsTheirNextRoundRetries() {
        AiToolRetryTracker tracker = new AiToolRetryTracker();
        AiPendingTool first = tool("first", 10L, Map.of("value", "bad-a"));
        AiPendingTool second = tool("second", 10L, Map.of("value", "bad-b"));
        tracker.failed(first);

        assertThat(tracker.retry(second, false)).isEmpty();
        tracker.failed(second);

        assertThat(tracker.retry(tool("retry-first", 11L, Map.of("value", "good-a")), false))
                .hasValue(new AiToolRetryTracker.RetryNotice("create_item", true));
        assertThat(tracker.retry(tool("retry-second", 11L, Map.of("value", "good-b")), false))
                .hasValue(new AiToolRetryTracker.RetryNotice("create_item", true));
    }

    @Test
    void consumesTheMatchedFailureWhenTheModelAlreadyNarratedTheRetry() {
        AiToolRetryTracker tracker = new AiToolRetryTracker();
        tracker.failed(tool("failed", 10L, Map.of("value", "bad")));

        assertThat(tracker.retry(tool("narrated-retry", 11L, Map.of("value", "good")), true))
                .isEmpty();
        assertThat(tracker.retry(tool("later-call", 12L, Map.of("value", "other")), false))
                .isEmpty();
    }

    private AiPendingTool tool(String id, long modelStepId, Object arguments) {
        return new AiPendingTool(id, "create_item", arguments,
                new AiObservationAccumulator(AiChatStepId.from(modelStepId), List.of()),
                modelStepId);
    }
}
