package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.junit.jupiter.api.Test;

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
        stored.put("context_estimated", true);
        stored.put("context_scope", "subagent");
        stored.put("extra", Map.of("cache_creation_input_tokens", 5L));

        Map<String, Object> normalized = service.normalizeMetrics(stored);

        assertThat(normalized).containsOnlyKeys(
                "prompt_tokens", "completion_tokens", "cached_tokens", "extra");
        assertThat(map(normalized.get("extra")))
                .containsEntry("cache_creation_input_tokens", 5L)
                .containsEntry("context_input_tokens", 12_909L)
                .containsEntry("context_estimated", true)
                .containsEntry("context_scope", "subagent");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
