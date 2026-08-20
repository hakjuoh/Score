package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowType;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AssignedAgentTest {

    @Test
    void bindsAnAssignmentToTheDurableChildAndCurrentWorkflow() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."));
        AgentExecutionService executor = mock(AgentExecutionService.class);
        AgentInstructions instructions = mock(AgentInstructions.class);
        when(instructions.render(any(AgentInstructions.Template.class), anyMap()))
                .thenReturn(new Agent.Instruction("Bounded assignment context."));
        when(instructions.render(eq(AgentInstructions.Template.WORKER_PROGRESS), eq(Map.of())))
                .thenReturn(new Agent.Instruction("Write a guide before every Tool round."));
        when(executor.executeChat(any(AgentChatSession.class))).thenReturn(new AgentChatResult(
                "verified", Map.of("modelId", "model")));

        AiTrajectoryRecorder rootRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childRecorder = mock(AiTrajectoryRecorder.class);
        when(rootRecorder.forkSubagent(eq("researcher"), any(), anyMap()))
                .thenReturn(childRecorder);
        when(childRecorder.conversationId()).thenReturn("durable-child-42");
        ChatRequest chatRequest = new ChatRequest("Inspect it", "request-1", null,
                "conversation-1", null, List.of(), null,
                "model", "high", "ask");
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                chatRequest, List.of(), new AiMessage.User("Inspect it"), null, rootRecorder,
                true, false, AgentToolPolicy.READ_ONLY, 0,
                AiChangeApprovalScope.root("conversation-1"))
                .withGuardrailDecisions(List.of("guard-1"));

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
                                3, "balanced", "agents", true, false, false), 3)
                .forIteration(plan, null, 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "research-work", "main:1:research-work", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "research", task, List.of(upstream));

        AssignedAgentHandlers handlers = new AssignedAgentHandlers(instructions);
        AssignedAgent assigned = new AssignedAgent(definition, handlers.requestHandler(),
                handlers.responseHandler());
        AgentDecision decision = new AgentRunner(executor, null, null, instructions,
                List.of(assigned)).run(assigned.callId(), context);

        assertThat(((AgentDecision.Complete) decision).result().content())
                .isEqualTo("verified");
        var child = org.mockito.ArgumentCaptor.forClass(AgentChatSession.class);
        verify(executor).executeChat(child.capture());
        ChatExecutionContext childContext = (ChatExecutionContext) child.getValue().context();
        assertThat(child.getValue().instruction().value())
                .contains("Bounded assignment context.",
                        "Write a guide before every Tool round.");
        assertThat(childContext.conversationId())
                .isEqualTo("durable-child-42");
        assertThat(childContext.toolPolicy()).isEqualTo(AgentToolPolicy.READ_ONLY);
        assertThat(childContext.workflowObservationContext())
                .containsEntry("node_id", "main:1:research-work")
                .containsEntry("parent_node_id", "main");
        assertThat(childContext.guardrailDecisionIds())
                .containsExactly("guard-1");
        verify(instructions).render(eq(AgentInstructions.Template.WORKER_RESTRICTED),
                org.mockito.ArgumentMatchers.argThat(parameters ->
                        !parameters.containsKey("assignment")));
        verify(instructions).render(eq(AgentInstructions.Template.WORKER_PROGRESS),
                eq(Map.of()));
        verify(instructions).render(eq(AgentInstructions.Template.UPSTREAM_RESULTS),
                org.mockito.ArgumentMatchers.argThat(parameters ->
                        parameters.get("results").toString().contains("earlier evidence")));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> completedMetadata =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(childRecorder).terminalLifecycle(eq("subagent_completed"), any(),
                completedMetadata.capture());
        assertThat(completedMetadata.getValue())
                .containsEntry("assignment", "Verify the current record.")
                .containsEntry("result", "verified");
    }

    @Test
    void explicitNestedDelegationHandsTheAssignmentBackToThePlannerWithoutCallingTheWorker() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."));
        AgentExecutionService executor = mock(AgentExecutionService.class);
        AgentInstructions instructions = mock(AgentInstructions.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatRequest request = new ChatRequest("Inspect it", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask");
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                request, List.of(), new AiMessage.User("Inspect it"), null, recorder,
                true, false, AgentToolPolicy.READ_ONLY, 0,
                AiChangeApprovalScope.root("conversation-1"));
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher", "Nested verification",
                "Spawn exactly 2 sub-agents in parallel and combine their findings.",
                "I’ll delegate two independent checks.", "Delegating", "Verified",
                AiWorkflowPlan.ToolAccess.READ_ONLY, AiWorkflowPlan.Delegation.FAN_OUT);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("outer", List.of(
                        new AiWorkflowPlan.Member("nested", task, null))), null, null);
        AgentWorkflowContext context = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request-1", "conversation-1",
                                "user-1", "model", "Inspect it", false, false,
                                3, "balanced", "agents", true, false, false), 3)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "outer", "main:1:outer", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "nested", task, List.of());
        AssignedAgentHandlers handlers = new AssignedAgentHandlers(instructions);
        AssignedAgent assigned = new AssignedAgent(definition, handlers.requestHandler(),
                handlers.responseHandler());

        AgentDecision decision = new AgentRunner(executor, null, null, instructions,
                List.of(assigned)).run(assigned.callId(), context);

        assertThat(decision).isInstanceOfSatisfying(AgentDecision.Handoff.class,
                handoff -> assertThat(handoff.target()).isEqualTo(AssistantAgent.PLANNER_ID));
        verify(executor, never()).executeChat(any());
        verify(recorder, never()).forkSubagent(any(), any(), anyMap());
        verify(recorder).lifecycle(eq("subagent_started"), any(),
                org.mockito.ArgumentMatchers.argThat(metadata ->
                        metadata.get("assignment").equals(task.instruction())));
        verify(recorder, never()).lifecycle(eq("subagent_completed"), any(), anyMap());
    }

    @Test
    void reusesOneChildRecorderAndSettlesItsCumulativeUsageOnceAcrossRetries() {
        AtomicInteger executions = new AtomicInteger();
        AgentOutputGuardrail retryThenAllow = request -> executions.get() == 1
                ? new AgentOutputGuardrail.Result.Retry("rewrite safely",
                GuardrailDecision.of("assigned-output", "1", GuardrailDecision.Action.RETRY))
                : new AgentOutputGuardrail.Result.Allow(request.candidate(),
                GuardrailDecision.of("assigned-output", "1", GuardrailDecision.Action.ALLOW));
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."),
                AgentRequestHandler.defaultRequest(), AgentToolHandler.none(),
                AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(), List.of(retryThenAllow)), true);
        AgentExecutionService executor = mock(AgentExecutionService.class);
        when(executor.executeChat(any(AgentChatSession.class))).thenAnswer(invocation -> {
            int attempt = executions.incrementAndGet();
            return new AgentChatResult(attempt == 1 ? "unsafe" : "safe", Map.of(),
                    Optional.of(new AgentRunResult.Usage(4, 2, 1)));
        });
        AgentInstructions instructions = mock(AgentInstructions.class);
        when(instructions.render(any(AgentInstructions.Template.class), anyMap()))
                .thenReturn(new Agent.Instruction("Bounded assignment context."));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        AiUsageSnapshot cumulative = new AiUsageSnapshot(
                "member", "Researcher", 8, 4, 2);
        when(root.forkSubagent(eq("researcher"), any(), anyMap())).thenReturn(child);
        when(child.conversationId()).thenReturn("durable-child-42");
        when(child.usageSnapshot()).thenReturn(cumulative);
        ChatRequest request = new ChatRequest("Inspect it", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask");
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                request, List.of(), new AiMessage.User("Inspect it"), null, root,
                false, false, AgentToolPolicy.NONE, 0,
                AiChangeApprovalScope.root("conversation-1"));
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher", "Research", "Verify the current record.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("research-work", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        List<AiUsageSnapshot> directUsage = new java.util.ArrayList<>();
        AtomicInteger registrations = new AtomicInteger();
        AtomicReference<Supplier<AiUsageSnapshot>> registered = new AtomicReference<>();
        WorkflowRunControl control = new WorkflowRunControl() {
            @Override public void checkpoint() { }
            @Override public void recordUsage(AiUsageSnapshot snapshot) {
                directUsage.add(snapshot);
            }
            @Override public void registerUsage(Supplier<AiUsageSnapshot> source,
                                                Runnable lateWriteFence) {
                registrations.incrementAndGet();
                registered.set(source);
            }
        };
        AgentWorkflowContext context = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request-1", "conversation-1",
                                "user", "model", "Inspect it", false, false,
                                1, "balanced", null, true, false, false), 1, control)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "research-work", "main:research-work", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "member", task, List.of());
        AssignedAgentHandlers handlers = new AssignedAgentHandlers(instructions);
        AssignedAgent assigned = new AssignedAgent(definition, handlers.requestHandler(),
                handlers.responseHandler());

        AgentDecision decision = new AgentRunner(executor, null, null, instructions,
                List.of(assigned)).run(assigned.callId(), context);

        assertThat(((AgentDecision.Complete) decision).result().content()).isEqualTo("safe");
        assertThat(executions).hasValue(2);
        assertThat(registrations).hasValue(1);
        assertThat(directUsage).isEmpty();
        assertThat(registered.get().get()).isEqualTo(cumulative);
        verify(root, times(1)).forkSubagent(eq("researcher"), any(), anyMap());
        verify(child).lifecycle(eq("subagent_retry"), any(), anyMap());
        verify(child).terminalLifecycle(eq("subagent_completed"), any(), anyMap());
    }

    @Test
    void inputRefusalTerminatesThePreparedChildAndRegistersItsUsageOnce() {
        AgentInputGuardrail refuse = request -> new AgentInputGuardrail.Result.Refuse(
                new GuardrailRefusal(GuardrailDecision.of(
                        "assigned-input", "1", GuardrailDecision.Action.REFUSE),
                        "BLOCKED", "ai.policy.refused"));
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."),
                AgentRequestHandler.defaultRequest(), AgentToolHandler.none(),
                AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(refuse), List.of()), true);
        AgentExecutionService executor = mock(AgentExecutionService.class);
        AgentInstructions instructions = mock(AgentInstructions.class);
        when(instructions.render(any(AgentInstructions.Template.class), anyMap()))
                .thenReturn(new Agent.Instruction("Bounded assignment context."));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.forkSubagent(eq("researcher"), any(), anyMap())).thenReturn(child);
        when(child.conversationId()).thenReturn("durable-child-42");
        ChatRequest request = new ChatRequest("Inspect it", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask");
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                request, List.of(), new AiMessage.User("Inspect it"), null, root,
                false, false, AgentToolPolicy.NONE, 0,
                AiChangeApprovalScope.root("conversation-1"));
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher", "Research", "Verify the current record.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("research-work", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        AtomicInteger registrations = new AtomicInteger();
        WorkflowRunControl control = new WorkflowRunControl() {
            @Override public void checkpoint() { }
            @Override public void recordUsage(AiUsageSnapshot usage) { }
            @Override public void registerUsage(Supplier<AiUsageSnapshot> source,
                                                Runnable lateWriteFence) {
                registrations.incrementAndGet();
            }
        };
        AgentWorkflowContext context = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request-1", "conversation-1",
                                "user", "model", "Inspect it", false, false,
                                1, "balanced", null, true, false, false), 1, control)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "research-work", "main:research-work", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "member", task, List.of());
        AssignedAgentHandlers handlers = new AssignedAgentHandlers(instructions);
        AssignedAgent assigned = new AssignedAgent(definition, handlers.requestHandler(),
                handlers.responseHandler());

        assertThatThrownBy(() -> new AgentRunner(executor, null, null, instructions,
                List.of(assigned)).run(assigned.callId(), context))
                .isInstanceOf(AgentGuardrailRefusedException.class);

        assertThat(registrations).hasValue(1);
        verify(root, times(1)).forkSubagent(eq("researcher"), any(), anyMap());
        verify(child).terminalLifecycle(eq("subagent_refused"), any(), anyMap());
        verify(executor, never()).executeChat(any());
    }

    @Test
    void classifiesCancellationAsCancellationInsteadOfFailure() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("worker"),
                "Worker", "Worker", new AgentDefinition.InstructionTemplate("Safe."));
        AgentExecutionService executor = mock(AgentExecutionService.class);
        when(executor.executeChat(any(AgentChatSession.class))).thenThrow(new CancellationException("stopped"));
        AgentInstructions instructions = mock(AgentInstructions.class);
        when(instructions.render(any(AgentInstructions.Template.class), anyMap()))
                .thenReturn(new Agent.Instruction("Bounded."));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.forkSubagent(eq("worker"), any(), anyMap())).thenReturn(child);
        when(child.conversationId()).thenReturn("child");
        ChatRequest request = new ChatRequest("prompt", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask");
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                request, List.of(), new AiMessage.User("prompt"), null, root,
                false, false, AgentToolPolicy.NONE, 0,
                AiChangeApprovalScope.root("conversation-1"));
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "worker", "Work", "Do work", null, null, null,
                AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("work", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        AgentWorkflowContext context = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request-1", "conversation-1",
                                "user", "model", "prompt", false, false,
                                1, "balanced", null, true, false, false), 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "work", "main:work", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "member", task, List.of());

        AssignedAgentHandlers handlers = new AssignedAgentHandlers(instructions);
        AssignedAgent assigned = new AssignedAgent(definition, handlers.requestHandler(),
                handlers.responseHandler());
        assertThatThrownBy(() -> new AgentRunner(executor, null, null, instructions,
                List.of(assigned)).run(assigned.callId(), context))
                .isInstanceOf(CancellationException.class);
        verify(child).terminalLifecycle(eq("subagent_cancelled"), any(), anyMap());
        verify(child, never()).terminalLifecycle(eq("subagent_failed"), any(), anyMap());
    }
}
