package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Correlated tool calls and observations for one stored step. */
public record AiObservationAccumulator(
        AiChatStepId stepId,
        List<Map<String, Object>> toolCalls,
        Map<String, Map<String, Object>> results) {

    public AiObservationAccumulator(AiChatStepId stepId,
                                    List<Map<String, Object>> toolCalls) {
        this(stepId, List.copyOf(toolCalls), new ConcurrentHashMap<>());
    }

    public List<Map<String, Object>> orderedResults() {
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Map<String, Object> call : toolCalls) {
            Map<String, Object> result = results.get(Objects.toString(call.get("tool_call_id"), ""));
            if (result != null) {
                ordered.add(result);
            }
        }
        if (ordered.isEmpty()) {
            ordered.addAll(results.values());
        }
        return ordered;
    }
}
