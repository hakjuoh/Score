package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AtifTrajectoryServiceTest {

    private final AtifTrajectoryService service = new AtifTrajectoryService();

    @Test
    void collapsesAnIdenticalUiProjectionIntoItsModelInference() {
        Map<String, Object> modelCall = modelCall("Hello", Map.of(
                "prompt_tokens", 10L,
                "completion_tokens", 2L));

        var result = service.normalizeSteps(List.of(modelCall, projection("Hello")));

        assertThat(result.collapsedUiProjections()).isEqualTo(1);
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step).containsEntry("step_id", 1)
                    .containsEntry("message", "Hello")
                    .containsEntry("llm_call_count", 1);
            assertThat(map(step.get("metrics")))
                    .containsEntry("prompt_tokens", 10L)
                    .containsEntry("completion_tokens", 2L);
            assertThat(map(step.get("extra")))
                    .containsEntry("message_kind", "model_call")
                    .containsEntry("visibility", "visible")
                    .containsEntry("ui_projection", true)
                    .containsEntry("projection_collapsed", true)
                    .containsEntry("projection_message_kind", "assistant")
                    .containsEntry("projection_timestamp", "2026-07-20T17:43:17.578322Z");
        });
    }

    @Test
    void suppliesStreamedFinalTextToAnOtherwiseEmptyFinalModelInference() {
        var result = service.normalizeSteps(List.of(
                modelCall("", Map.of("prompt_tokens", 20L, "completion_tokens", 8L)),
                projection("Streamed final answer")));

        assertThat(result.collapsedUiProjections()).isEqualTo(1);
        assertThat(result.steps()).singleElement().satisfies(step ->
                assertThat(step).containsEntry("message", "Streamed final answer")
                        .containsEntry("llm_call_count", 1));
    }

    @Test
    void preservesAProjectionWhenTheLatestModelTextIsMateriallyDifferent() {
        var result = service.normalizeSteps(List.of(
                modelCall("Raw provider answer", Map.of()),
                projection("Application-composed answer")));

        assertThat(result.collapsedUiProjections()).isZero();
        assertThat(result.steps()).extracting(step -> step.get("message"))
                .containsExactly("Raw provider answer", "Application-composed answer");
    }

    @Test
    void movesProducerSpecificMetricsUnderTheAtifExtraField() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("prompt_tokens", 10L);
        stored.put("completion_tokens", 3L);
        stored.put("cached_tokens", 4L);
        stored.put("context_input_tokens", 12_909L);
        stored.put("context_estimated", false);
        stored.put("context_scope", "subagent");
        stored.put("extra", Map.of("cache_creation_input_tokens", 5L));

        Map<String, Object> normalized = service.normalizeMetrics(stored);

        assertThat(normalized).containsOnlyKeys(
                "prompt_tokens", "completion_tokens", "cached_tokens", "extra");
        assertThat(map(normalized.get("extra")))
                .containsEntry("cache_creation_input_tokens", 5L)
                .containsEntry("context_input_tokens", 12_909L)
                .containsEntry("context_estimated", false)
                .containsEntry("context_scope", "subagent");
    }

    @Test
    void movesAnIncompleteProviderPromptCountOutOfTheStandardAtifField() {
        Map<String, Object> normalized = service.normalizeMetrics(Map.of(
                "prompt_tokens", 2L,
                "completion_tokens", 4L,
                "context_input_tokens", 5_000L,
                "context_estimated", true));

        assertThat(normalized).doesNotContainKey("prompt_tokens")
                .containsEntry("completion_tokens", 4L);
        assertThat(map(normalized.get("extra")))
                .containsEntry("provider_reported_prompt_tokens", 2L)
                .containsEntry("prompt_tokens_complete", false)
                .containsEntry("context_input_tokens", 5_000L)
                .containsEntry("context_estimated", true);
    }

    @Test
    void preservesProviderVerifiedPromptTokensAboveAnEstimatedContextFloor() {
        Map<String, Object> normalized = service.normalizeMetrics(Map.of(
                "prompt_tokens", 107L,
                "prompt_tokens_complete", true,
                "prompt_token_accounting", "cache_included",
                "context_input_tokens", 5_000L,
                "context_estimated", true));

        assertThat(normalized).containsEntry("prompt_tokens", 107L);
        assertThat(map(normalized.get("extra")))
                .containsEntry("prompt_tokens_complete", true)
                .containsEntry("prompt_token_accounting", "cache_included")
                .containsEntry("context_input_tokens", 5_000L)
                .containsEntry("context_estimated", true);
    }

    @Test
    void omitsIncompleteFinalTotalsAndReportsMeasurementCoverage() {
        AiChatTrajectoryData data = new AiChatTrajectoryData(
                "conversation-1", 2L, false,
                List.of(
                        storedModelStep(1L, "gateway", Map.of(),
                                Instant.parse("2026-07-20T17:43:17Z")),
                        storedModelStep(2L, "assistant", Map.of(
                                        "prompt_tokens", 2L,
                                        "completion_tokens", 4L,
                                        "context_input_tokens", 5_000L,
                                        "context_estimated", true),
                                Instant.parse("2026-07-20T17:43:18Z"))),
                List.of());

        Map<String, Object> trajectory = service.export(data, "3.6.0", "claude-fable-5");
        Map<String, Object> finalMetrics = map(trajectory.get("final_metrics"));

        assertThat(finalMetrics).containsEntry("total_steps", 2)
                .doesNotContainKeys("total_prompt_tokens", "total_completion_tokens",
                        "total_cached_tokens");
        Map<String, Object> extra = map(finalMetrics.get("extra"));
        assertThat(extra).containsEntry("metrics_complete", false)
                .containsEntry("tracked_llm_calls", 2)
                .containsEntry("untracked_llm_steps", 0);
        assertThat(map(extra.get("measured_llm_calls")))
                .containsEntry("prompt_tokens", 0)
                .containsEntry("completion_tokens", 1)
                .containsEntry("cached_tokens", 0);
        assertThat(map(extra.get("partial_totals")))
                .containsOnly(Map.entry("completion_tokens", 4L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trajectory.get("steps");
        assertThat(map(steps.get(1).get("metrics"))).doesNotContainKey("prompt_tokens");
    }

    @Test
    void keepsEmbeddedSubagentTotalsIndependentFromTheRootTrajectory() {
        AiChatTrajectoryData.Step rootStep = storedModelStep(1L, "root", Map.of(
                        "prompt_tokens", 10L, "completion_tokens", 5L, "cached_tokens", 0L),
                Instant.parse("2026-07-20T17:43:18Z"));
        AiChatTrajectoryData.Step childStep = storedModelStep(1L, "child", Map.of(
                        "prompt_tokens", 20L, "completion_tokens", 7L, "cached_tokens", 3L),
                Instant.parse("2026-07-20T17:43:17Z"));
        AiChatTrajectoryData data = new AiChatTrajectoryData(
                "conversation-1", 2L, false, List.of(rootStep),
                List.of(new AiChatTrajectoryData.ChildTrajectory(
                        "child-1", "SUBAGENT", "worker", "request-1", List.of(childStep))));

        Map<String, Object> trajectory = service.export(data, "3.6.0", "claude-fable-5");
        assertThat(map(trajectory.get("final_metrics")))
                .containsEntry("total_prompt_tokens", 10L)
                .containsEntry("total_completion_tokens", 5L)
                .containsEntry("total_cached_tokens", 0L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children =
                (List<Map<String, Object>>) trajectory.get("subagent_trajectories");
        assertThat(map(children.getFirst().get("final_metrics")))
                .containsEntry("total_prompt_tokens", 20L)
                .containsEntry("total_completion_tokens", 7L)
                .containsEntry("total_cached_tokens", 3L);
    }

    @Test
    void treatsAggregatedStepMetricsAsCoveringEveryRepresentedLlmCall() {
        AiChatTrajectoryData.Step aggregatedStep = storedModelStep(
                1L, "aggregated", Map.of(
                        "prompt_tokens", 30L,
                        "completion_tokens", 7L,
                        "cached_tokens", 4L),
                2,
                Instant.parse("2026-07-20T17:43:18Z"));
        AiChatTrajectoryData data = new AiChatTrajectoryData(
                "conversation-1", 2L, false, List.of(aggregatedStep), List.of());

        Map<String, Object> trajectory = service.export(data, "3.6.0", "claude-fable-5");
        Map<String, Object> finalMetrics = map(trajectory.get("final_metrics"));

        assertThat(finalMetrics)
                .containsEntry("total_prompt_tokens", 30L)
                .containsEntry("total_completion_tokens", 7L)
                .containsEntry("total_cached_tokens", 4L);
        assertThat(map(finalMetrics.get("extra")))
                .containsEntry("metrics_complete", true)
                .containsEntry("tracked_llm_calls", 2);
        assertThat(map(map(finalMetrics.get("extra")).get("measured_llm_calls")))
                .containsOnly(
                        Map.entry("prompt_tokens", 2),
                        Map.entry("completion_tokens", 2),
                        Map.entry("cached_tokens", 2));
    }

    @Test
    void keepsSystemProducerMeasurementsOutOfAgentOnlyMetrics() {
        Map<String, Object> step = new LinkedHashMap<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        service.addMetrics(step, extra, "system", Map.of(
                "fanout_prompt_tokens", 30L,
                "context_input_tokens", 1_000L,
                "context_estimated", true));

        assertThat(step).doesNotContainKey("metrics");
        assertThat(map(extra.get("producer_metrics"))).containsOnlyKeys("extra");
        assertThat(map(map(extra.get("producer_metrics")).get("extra")))
                .containsEntry("fanout_prompt_tokens", 30L)
                .containsEntry("context_input_tokens", 1_000L)
                .containsEntry("context_estimated", true);
    }

    private Map<String, Object> modelCall(String message, Map<String, Object> metrics) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step_id", 1);
        step.put("timestamp", "2026-07-20T17:43:17.544382Z");
        step.put("source", "agent");
        step.put("model_name", "claude-sonnet-5");
        step.put("message", message);
        if (!metrics.isEmpty()) step.put("metrics", new LinkedHashMap<>(metrics));
        step.put("extra", new LinkedHashMap<>(Map.of(
                "request_id", "request-1", "message_kind", "model_call",
                "visibility", "debug", "provider_response_id", "response-1")));
        step.put("llm_call_count", 1);
        return step;
    }

    private Map<String, Object> projection(String message) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step_id", 2);
        step.put("timestamp", "2026-07-20T17:43:17.578322Z");
        step.put("source", "agent");
        step.put("model_name", "claude-sonnet-5");
        step.put("message", message);
        step.put("extra", new LinkedHashMap<>(Map.of(
                "request_id", "request-1", "message_kind", "assistant",
                "visibility", "visible", "ui_projection", true,
                "permission_mode", "auto")));
        step.put("llm_call_count", 0);
        return step;
    }

    private AiChatTrajectoryData.Step storedModelStep(
            long sequence, String requestId, Map<String, Object> metrics, Instant createdAt) {
        return storedModelStep(sequence, requestId, metrics, 1, createdAt);
    }

    private AiChatTrajectoryData.Step storedModelStep(
            long sequence, String requestId, Map<String, Object> metrics,
            Integer llmCallCount, Instant createdAt) {
        return new AiChatTrajectoryData.Step(
                sequence, requestId, "agent", "model_call", "debug", "", null,
                "claude-fable-5", "high", null, null, metrics, Map.of(),
                llmCallCount, false, createdAt);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
