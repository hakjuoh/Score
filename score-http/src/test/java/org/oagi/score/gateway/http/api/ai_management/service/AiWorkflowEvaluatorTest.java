package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntimeRegistry;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkflowEvaluatorTest {

    @Test
    void returnsStructuredContinueFeedbackWithoutTools() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowEvaluator evaluator = new AiWorkflowEvaluator(
                runtimes, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {"decision":"CONTINUE","feedback":"The mutation lacks read-back.",
                 "nextObjective":"Read the target again and verify the saved values."}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        when(root.executedDomainToolCallCount()).thenReturn(4L);
        when(root.pendingApprovalCount()).thenReturn(1L);
        ChatRequest request = new ChatRequest(
                "Update the record", "request-1", null, "conversation-1", null,
                List.of(), null, "model", "high", "default", Map.of(), "ask");
        AiRuntime.Context context = new AiRuntime.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "direct", true, "Updating.", "Updating", "Updated",
                null, "Verifying", "Verified", List.of());

        AiWorkflowEvaluation result = evaluator.evaluate(
                context, plan, new AiRuntime.Result("Updated."), 1, 3);

        assertThat(result.decision()).isEqualTo(AiWorkflowEvaluation.Decision.CONTINUE);
        assertThat(result.nextObjective()).contains("verify the saved values");
        ArgumentCaptor<AiRuntime.Context> execution =
                ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes).execute(eq("default"), execution.capture());
        assertThat(execution.getValue().toolsEnabled()).isFalse();
        assertThat(execution.getValue().streamVisibleContent()).isFalse();
        // The grounding evidence keys are a contract with the evaluator prompt:
        // request-wide executed domain calls and pending approvals, not the
        // root recorder's local counters.
        assertThat(((SystemMessage) execution.getValue().history().getFirst()).getText())
                .contains("Original user request", "Workflow result", "Maximum iterations: 3")
                .contains("\"executedDomainToolCalls\":4", "\"pendingApprovals\":1")
                .doesNotContain("${");
    }

    @Test
    void preservesContinueAtTheIterationCapSoTheControllerReportsTheLimit() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowEvaluator evaluator = new AiWorkflowEvaluator(
                runtimes, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {"decision":"CONTINUE","feedback":"Evidence is still missing.",
                 "nextObjective":"Retrieve the missing evidence."}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Investigate", "request-1", null, "conversation-1", null,
                List.of(), null, "model", "high", "default", Map.of(), "ask");
        AiRuntime.Context context = new AiRuntime.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "direct", true, "Investigating.", "Investigating", "Investigated",
                null, "Verifying", "Verified", List.of());

        AiWorkflowEvaluation result = evaluator.evaluate(
                context, plan, new AiRuntime.Result("Still incomplete."), 3, 3);

        assertThat(result.decision()).isEqualTo(AiWorkflowEvaluation.Decision.CONTINUE);
        assertThat(result.nextObjective()).isEqualTo("Retrieve the missing evidence.");
    }
}
