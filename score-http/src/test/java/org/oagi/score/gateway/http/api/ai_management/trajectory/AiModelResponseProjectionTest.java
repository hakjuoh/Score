package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
}
