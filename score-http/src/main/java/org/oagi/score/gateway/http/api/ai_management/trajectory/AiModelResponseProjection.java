package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiResponseContent;
import org.oagi.score.gateway.http.api.ai_management.model.AiMetricsSnapshot;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Projects one provider response into audited trajectory content and normalized usage. */
final class AiModelResponseProjection {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private AiModelResponseProjection() {
    }

    static Content content(List<Generation> generations, ObjectMapper objectMapper,
                           Function<Object, Object> auditValue) {
        List<Map<String, Object>> calls = toolCalls(generations, objectMapper);
        List<Map<String, Object>> audited = calls.stream().map(call -> {
            Map<String, Object> item = new LinkedHashMap<>(call);
            item.put("arguments", Objects.requireNonNull(
                    auditValue.apply(call.get("arguments")), "audited tool arguments"));
            return Map.copyOf(item);
        }).toList();
        return new Content(SpringAiResponseContent.reasoning(generations),
                SpringAiResponseContent.visibleStored(generations), calls, audited);
    }

    /** Normalizes provider usage and monotonically advances the caller's input estimate floor. */
    static AiMetricsSnapshot metrics(ChatResponse response, boolean streaming,
                                     ProviderPromptTokenNormalizer normalizer,
                                     AtomicLong estimatedInputFloor, boolean subagentScope) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) return null;
        Map<String, Object> metrics = new LinkedHashMap<>();
        ProviderPromptTokenNormalizer.Snapshot prompt = normalizer.normalize(usage, streaming);
        long contextInputTokens = Math.max(prompt.inclusiveTokens(), estimatedInputFloor.get());
        boolean contextEstimated = !prompt.complete()
                || contextInputTokens > prompt.inclusiveTokens();
        if (!prompt.complete()) {
            metrics.put("provider_reported_prompt_tokens", prompt.providerReportedTokens());
            metrics.put("prompt_tokens_complete", false);
        } else {
            metrics.put("prompt_tokens", prompt.inclusiveTokens());
            metrics.put("prompt_tokens_complete", true);
        }
        metrics.put("prompt_token_accounting", normalizer.wireValue());
        putIfPresent(metrics, "completion_tokens", usage.getCompletionTokens());
        putIfPresent(metrics, "cached_tokens", usage.getCacheReadInputTokens());
        if (usage.getCacheWriteInputTokens() != null) {
            metrics.put("extra", Map.of(
                    "cache_creation_input_tokens", usage.getCacheWriteInputTokens()));
        }
        estimatedInputFloor.accumulateAndGet(contextInputTokens, Math::max);
        metrics.put("context_input_tokens", contextInputTokens);
        metrics.put("context_estimated", contextEstimated);
        if (subagentScope) metrics.put("context_scope", "subagent");
        return new AiMetricsSnapshot(Map.copyOf(metrics), contextInputTokens, contextEstimated);
    }

    private static List<Map<String, Object>> toolCalls(List<Generation> generations,
                                                       ObjectMapper objectMapper) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Generation generation : generations) {
            for (AssistantMessage.ToolCall call : generation.getOutput().getToolCalls()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tool_call_id", StringUtils.hasText(call.id())
                        ? call.id() : UUID.randomUUID().toString());
                item.put("function_name", call.name());
                item.put("arguments", arguments(call.arguments(), objectMapper));
                calls.add(item);
            }
        }
        return calls;
    }

    static Map<String, Object> arguments(String json, ObjectMapper objectMapper) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException exception) {
            return Map.of("raw", json);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    record Content(String reasoning, String visible, List<Map<String, Object>> toolCalls,
                   List<Map<String, Object>> auditedToolCalls) {
    }
}
