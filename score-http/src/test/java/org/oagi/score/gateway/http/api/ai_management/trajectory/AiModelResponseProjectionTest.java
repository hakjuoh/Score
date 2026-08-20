package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelResponseProjectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsReasoningVisibleTextAndAuditedToolArgumentsIndependently() {
        Generation reasoning = new Generation(AssistantMessage.builder()
                .content("inspect first")
                .properties(Map.of("signature", "thinking"))
                .build());
        Generation answerAndTool = new Generation(AssistantMessage.builder()
                .content("working")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "lookup", "{\"secret\":\"value\"}")))
                .build());

        AiModelResponseProjection.Content content = AiModelResponseProjection.content(
                List.of(reasoning, answerAndTool), objectMapper, ignored -> Map.of("redacted", true));

        assertThat(content.reasoning()).isEqualTo("inspect first");
        assertThat(content.visible()).isEqualTo("working");
        assertThat(content.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.get("arguments")).isEqualTo(Map.of("secret", "value")));
        assertThat(content.auditedToolCalls()).singleElement().satisfies(call ->
                assertThat(call.get("arguments")).isEqualTo(Map.of("redacted", true)));
    }

    @Test
    void preservesMalformedArgumentsAsRawAuditInput() {
        assertThat(AiModelResponseProjection.arguments("{broken", objectMapper))
                .containsExactly(Map.entry("raw", "{broken"));
        assertThat(AiModelResponseProjection.arguments("null", objectMapper)).isEmpty();
        assertThat(AiModelResponseProjection.arguments("  ", objectMapper)).isEmpty();
    }

    @Test
    void generatesOneStableIdForRawAndAuditedViewsOfAnUnidentifiedCall() {
        Generation toolCall = new Generation(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "  ", "function", "lookup", "{}")))
                .build());

        AiModelResponseProjection.Content content = AiModelResponseProjection.content(
                List.of(toolCall), objectMapper, value -> value);

        String rawId = (String) content.toolCalls().getFirst().get("tool_call_id");
        String auditedId = (String) content.auditedToolCalls().getFirst().get("tool_call_id");
        assertThat(rawId).isNotBlank().isEqualTo(auditedId);
    }

    @Test
    void appliesEstimateFloorAndMarksUnknownProviderAccountingIncomplete() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(7, 3, 10, null, 5L, 2L))
                        .build());
        AtomicLong floor = new AtomicLong(100L);

        var metrics = AiModelResponseProjection.metrics(response, false,
                ProviderPromptTokenNormalizer.forProvider("custom"), floor, true);

        assertThat(metrics.metrics())
                .containsEntry("provider_reported_prompt_tokens", 7L)
                .containsEntry("prompt_tokens_complete", false)
                .containsEntry("prompt_token_accounting", "unknown")
                .containsEntry("context_input_tokens", 100L)
                .containsEntry("context_estimated", true)
                .containsEntry("context_scope", "subagent");
        assertThat(metrics.contextInputTokens()).isEqualTo(100L);
        assertThat(floor).hasValue(100L);
    }

    @Test
    void replacesAConservativeFloorWithCompleteProviderPromptUsage() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(107, 3, 110, null, 100L, 0L))
                        .build());
        AtomicLong floor = new AtomicLong(5_000L);

        var metrics = AiModelResponseProjection.metrics(response, false,
                ProviderPromptTokenNormalizer.forProvider("openai"), floor, false);

        assertThat(metrics.metrics())
                .containsEntry("prompt_tokens", 107L)
                .containsEntry("prompt_tokens_complete", true)
                .containsEntry("context_input_tokens", 107L)
                .containsEntry("context_estimated", false);
        assertThat(metrics.contextInputTokens()).isEqualTo(107L);
        assertThat(floor).hasValue(107L);
    }

    @Test
    void completeProviderUsageRestoresCapacityForTheNextToolResult() {
        AtomicLong floor = new AtomicLong(100L);
        AiToolOutputLimiter limiter = new AiToolOutputLimiter(
                new AiContextBudget("model", 120L, 10L, 90L, 10L, 100L, false),
                floor, false);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("continue"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(20, 3, 23))
                        .build());

        AiModelResponseProjection.metrics(response, false,
                ProviderPromptTokenNormalizer.forProvider("openai"), floor, false);
        var bounded = limiter.reserve("read-back payload", 100L);

        assertThat(bounded.truncated()).isFalse();
        assertThat(bounded.value()).isEqualTo("read-back payload");
        assertThat(floor).hasValue(26L);
    }
}
