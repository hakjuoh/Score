package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiTrajectoryRecorderTest {

    @Test
    void doesNotRecordTheSyntheticApprovedContextResponseTwice() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});
        ToolResponseMessage response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("approved-1", "create_business_context",
                        "{\"biz_ctx_id\":18}"))).build();

        recorder.recordToolResponses(List.of(response));

        verifyNoInteractions(repository);
    }

    @Test
    void correlatesModelToolCallWithItsObservationAndUiDetail() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq(requester), eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);

        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "count_business_contexts", "{\"status\":\"active\"}");
        Generation reasoning = new Generation(AssistantMessage.builder()
                .content("Use the business context count tool.")
                .properties(Map.of("signature", "thinking"))
                .build());
        Generation toolRequest = new Generation(AssistantMessage.builder()
                .toolCalls(List.of(call))
                .build());

        ChatResponse response = new ChatResponse(List.of(reasoning, toolRequest),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(2, 7, 9, null, 100L, 5L))
                        .build());
        recorder.recordModelResponse(response, "worker:count");
        ToolCallbackProvider wrapped = recorder.recordingTools(() -> new ToolCallback[]{new CountTool()});
        String output = wrapped.getToolCallbacks()[0].call("{\"status\":\"active\"}",
                new ToolContext(Map.of()));

        assertThat(output).isEqualTo("{\"count\":12}");
        verify(repository).updateObservation(eq(requester), eq("conversation-1"), eq(42L), any());
        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq(requester), eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues().get(0).toolCalls()).singleElement()
                .satisfies(tool -> assertThat(tool.get("tool_call_id")).isEqualTo("call-1"));
        assertThat(steps.getAllValues().get(0).metrics())
                .containsEntry("prompt_tokens", 107L)
                .containsEntry("cached_tokens", 100L);
        assertThat(steps.getAllValues().get(1).messageKind()).isEqualTo("tool_call");
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("started", "completed");
        assertThat(events.get(1).metadata()).containsEntry("toolDetail", """
                count_business_contexts
                Arguments: {"status":"active"}
                Result: {"count":12}""");
        assertThat(steps.getAllValues().get(0).reasoningContent()).isNull();
        assertThat(steps.getAllValues().get(0).extra()).containsEntry("reasoning_present", true);
    }

    @Test
    void redactsSecretsFromPersistedToolArgumentsAndResults() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_secret_status").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenReturn("{\"api_key\":\"super-secret\","
                        + "\"Authorization\":\"Bearer exposed-token\","
                        + "\"session\":\"session-value\","
                        + "\"authorId\":42,\"sessionCount\":3,"
                        + "\"note\":\"cookie=browser-cookie, token: plain-token\",\"status\":\"ok\"}");

        recorder.recordingTools(() -> new ToolCallback[]{callback}).getToolCallbacks()[0]
                .call("{\"password\":\"hunter2\",\"credential\":\"credential-value\"}",
                        new ToolContext(Map.of()));

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq(requester), eq("conversation-1"), step.capture());
        assertThat(step.getValue().message()).contains("[REDACTED]")
                .contains("\"authorId\":42", "\"sessionCount\":3")
                .doesNotContain("super-secret", "exposed-token", "hunter2", "session-value",
                        "browser-cookie", "plain-token", "credential-value");
        assertThat(step.getValue().extra().toString()).contains("[REDACTED]")
                .doesNotContain("hunter2", "credential-value");
    }

    @Test
    void storesOnlyASafeMessageWhenAToolFailureContainsInternalDetails() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_business_context").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(new IllegalStateException(
                        "SQL syntax near app_user; Authorization=Bearer exposed-token"));

        assertThatThrownBy(() -> recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq(requester), eq("conversation-1"), step.capture());
        assertThat(step.getValue().message())
                .contains("Tool execution failed. Details were recorded in the server log.")
                .doesNotContain("SQL syntax", "app_user", "exposed-token");
        assertThat(events.getLast().metadata().get("toolDetail").toString())
                .doesNotContain("SQL syntax", "app_user", "exposed-token");
    }

    @Test
    void emitsAValidatedContextSnapshotAndUsesTheEstimateFloorForBrokenProviderUsage() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq(requester), eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(1L, 1L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiContextBudgetService.Budget budget = new AiContextBudgetService.Budget(
                "claude-fable-5", 200000L, 16000L, 150000L, 8192L, 32000L, false);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "claude-fable-5", "high", "claude", Map.of(),
                events::add, budget, 5000L);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, 4, 6)).build());

        recorder.recordModelResponse(response, "assistant");

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq(requester), eq("conversation-1"), step.capture());
        assertThat(step.getValue().metrics())
                .containsEntry("prompt_tokens", 2L)
                .containsEntry("context_input_tokens", 5000L)
                .containsEntry("context_estimated", true);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.subtype()).isEqualTo("context_usage");
            assertThat(event.metadata().get("contextUsage").toString())
                    .contains("currentInputTokens=5000", "estimated=true");
        });
    }

    @Test
    void truncatesAdversarialUnicodeToolOutputAtAValidUtf8Boundary() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_large_result").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("🙂".repeat(100));

        String output = recorder.recordingTools(() -> new ToolCallback[]{callback}, 96L)
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));

        assertThat(output.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(96 * 3);
        assertThat(output).contains("TOOL OUTPUT TRUNCATED").doesNotContain("�");
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("started", "completed", "tool_output_truncated");
    }

    @Test
    void capsToolOutputByTheRemainingContextBudgetAcrossTheActiveToolLoop() {
        ScoreChatMemoryRepository repository = mock(ScoreChatMemoryRepository.class);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_large_result").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("x".repeat(1000));
        AiContextBudgetService.Budget budget = new AiContextBudgetService.Budget(
                "model", 120L, 10L, 90L, 10L, 100L, false);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", "model", "high", "default",
                Map.of(), ignored -> {}, budget, 90L);

        String output = recorder.recordingTools(() -> new ToolCallback[]{callback}, 100L)
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));

        assertThat(output.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(10 * 3);
    }

    @Test
    void appliesTheRemainingContextBudgetToApprovedMutationResults() {
        AiContextBudgetService.Budget budget = new AiContextBudgetService.Budget(
                "model", 120L, 10L, 90L, 10L, 100L, false);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(mock(ScoreChatMemoryRepository.class),
                new ObjectMapper(), mock(ScoreUser.class), "conversation-1", "request-1", "model",
                "high", "default", Map.of(), events::add, budget, 90L);

        String output = recorder.limitToolOutput("x".repeat(1000), 100L,
                "update_business_context");

        assertThat(output.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(10 * 3);
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("tool_output_truncated", "context_usage");
        assertThat(events.getFirst().metadata())
                .containsEntry("toolName", "update_business_context");
    }

    @Test
    void returnsNoToolBytesWhenTheSafeInputBudgetIsAlreadyExhausted() {
        AiContextBudgetService.Budget budget = new AiContextBudgetService.Budget(
                "model", 120L, 10L, 90L, 10L, 100L, false);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(mock(ScoreChatMemoryRepository.class),
                new ObjectMapper(), mock(ScoreUser.class), "conversation-1", "request-1", "model",
                "high", "default", Map.of(), ignored -> {}, budget, budget.safeInputLimit());

        assertThat(recorder.limitToolOutput("must not fit", 100L, "get_result")).isEmpty();
    }

    private static final class CountTool implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("count_business_contexts")
                    .description("Counts business contexts")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String input) {
            return "{\"count\":12}";
        }

        @Override
        public String call(String input, ToolContext context) {
            return call(input);
        }
    }
}
