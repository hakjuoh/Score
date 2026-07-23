package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AssignedAgentTest {

    @Test
    void bindsAnAssignmentToTheDurableChildAndCurrentWorkflow() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."));
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AgentInstructions instructions = mock(AgentInstructions.class);
        when(instructions.render(any(AgentInstructions.Template.class), anyMap()))
                .thenReturn(new Agent.Instruction("Bounded assignment context."));
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result(
                "verified", Map.of("modelId", "model")));

        AiTrajectoryRecorder rootRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childRecorder = mock(AiTrajectoryRecorder.class);
        when(rootRecorder.forkSubagent(eq("researcher"), any(), anyMap()))
                .thenReturn(childRecorder);
        when(childRecorder.conversationId()).thenReturn("durable-child-42");
        AiChatExecutor.Context execution = mock(AiChatExecutor.Context.class);
        ChatRequest chatRequest = new ChatRequest("Inspect it", "request-1", null,
                "conversation-1", null, List.of(), null,
                "model", "high", "ask");
        when(execution.request()).thenReturn(chatRequest);
        when(execution.userMessage()).thenReturn(new UserMessage("Inspect it"));
        when(execution.recorder()).thenReturn(rootRecorder);
        when(execution.toolsEnabled()).thenReturn(true);
        when(execution.toolPolicy()).thenReturn(AiChatExecutor.ToolPolicy.READ_ONLY);
        when(execution.approvalScope()).thenReturn(
                AiMutationApprovalScope.root("conversation-1"));
        when(execution.guardrailDecisionIds()).thenReturn(List.of("guard-1"));

        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher", "Research", "Verify the current record.",
                "Researching", "Researching", "Researched",
                AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("research-work", List.of(
                        new AiWorkflowPlan.Member("research", task, null))), null, null);
        WorkflowResult upstream = WorkflowResult.success(
                "prior", "earlier evidence", Map.of(), List.of());
        AgentWorkflowContext context = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request-1", "conversation-1",
                                "user-1", "model", "Inspect it", false, false,
                                3, "balanced", "agents", true, false), 3)
                .forIteration(plan, null, 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "research-work", "main:1:research-work", "main", 1))
                .withAssignment(plan, "research", task, List.of(upstream));

        AgentDecision decision = new AssignedAgent(definition, executor, instructions)
                .execute(context);

        assertThat(((AgentDecision.Complete) decision).result().answer())
                .isEqualTo("verified");
        var child = org.mockito.ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(child.capture());
        assertThat(child.getValue().request().conversationId())
                .isEqualTo("durable-child-42");
        assertThat(child.getValue().toolPolicy())
                .isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY);
        assertThat(child.getValue().workflowObservationContext())
                .containsEntry("node_id", "main:1:research-work")
                .containsEntry("parent_node_id", "main");
        assertThat(child.getValue().guardrailDecisionIds()).containsExactly("guard-1");
        verify(instructions).render(eq(AgentInstructions.Template.WORKER_RESTRICTED),
                org.mockito.ArgumentMatchers.argThat(parameters ->
                        !parameters.containsKey("assignment")));
        verify(instructions).render(eq(AgentInstructions.Template.UPSTREAM_RESULTS),
                org.mockito.ArgumentMatchers.argThat(parameters ->
                        parameters.get("results").toString().contains("earlier evidence")));
        verify(childRecorder).terminalLifecycle(eq("subagent_completed"), any(), anyMap());
    }

    @Test
    void classifiesCancellationAsCancellationInsteadOfFailure() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("worker"),
                "Worker", "Worker", new AgentDefinition.InstructionTemplate("Safe."));
        AiChatExecutor executor = mock(AiChatExecutor.class);
        when(executor.execute(any())).thenThrow(new CancellationException("stopped"));
        AgentInstructions instructions = mock(AgentInstructions.class);
        when(instructions.render(any(AgentInstructions.Template.class), anyMap()))
                .thenReturn(new Agent.Instruction("Bounded."));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.forkSubagent(eq("worker"), any(), anyMap())).thenReturn(child);
        when(child.conversationId()).thenReturn("child");
        AiChatExecutor.Context execution = mock(AiChatExecutor.Context.class);
        ChatRequest request = new ChatRequest("prompt", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask");
        when(execution.request()).thenReturn(request);
        when(execution.userMessage()).thenReturn(new UserMessage("prompt"));
        when(execution.recorder()).thenReturn(root);
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "worker", "Work", "Do work", null, null, null,
                AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("work", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        AgentWorkflowContext context = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request-1", "conversation-1",
                                "user", "model", "prompt", false, false,
                                1, "balanced", null, true, false), 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "work", "main:work", "main", 1))
                .withAssignment(plan, "member", task, List.of());

        assertThatThrownBy(() -> new AssignedAgent(definition, executor, instructions)
                .execute(context)).isInstanceOf(CancellationException.class);
        verify(child).terminalLifecycle(eq("subagent_cancelled"), any(), anyMap());
        verify(child, never()).terminalLifecycle(eq("subagent_failed"), any(), anyMap());
    }
}
