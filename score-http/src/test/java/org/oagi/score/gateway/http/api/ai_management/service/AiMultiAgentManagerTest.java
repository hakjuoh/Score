package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntimeRegistry;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMultiAgentManagerTest {

    @Test
    void executesStableKnowledgeWithoutToolsOrGuide() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", false, null, "Answering", "Answered",
                null, "Answering", "Answered", List.of()));
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("stable answer"));

        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            assertThat(manager.execute(context(recorder, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("stable answer");
        }

        ArgumentCaptor<AiRuntime.Context> executed = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes).execute(eq("default"), executed.capture());
        assertThat(executed.getValue().toolsEnabled()).isFalse();
        assertThat(executed.getValue().toolPolicy()).isEqualTo(AiRuntime.ToolPolicy.NONE);
        verify(recorder, never()).guide(any(), any());
    }

    @Test
    void executesAForcedWorkerWorkflowWithoutGrantingUnneededTools() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "general-purpose", "General purpose", "general analysis",
                "Analyze the assignment.", AiRuntime.ToolPolicy.READ_ONLY);
        when(catalog.require(agent.id())).thenReturn(agent);
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Independent analysis", agent.id(), "Answer the stable-knowledge question.",
                null, "Analyzing", "Analyzed");
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "orchestrator_workers", false, "I’ll use the selected agent workflow.",
                "Analyzing", "Analyzed", "I’ll synthesize the answer.",
                "Synthesizing", "Synthesized", List.of(task)));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(child.conversationId()).thenReturn("child-1");
        when(root.forkSubagent(eq(agent.id()), eq(task.instruction()), any()))
                .thenReturn(child);
        when(runtimes.execute(eq("default"), any())).thenAnswer(invocation -> {
            AiRuntime.Context candidate = invocation.getArgument(1);
            return new AiRuntime.Result(candidate.agentDepth() == 1 ? "worker answer" : "final answer");
        });

        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            assertThat(manager.execute(context(root, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("final answer");
        }

        ArgumentCaptor<AiRuntime.Context> calls = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes, times(2)).execute(eq("default"), calls.capture());
        assertThat(calls.getAllValues()).allSatisfy(candidate -> {
            assertThat(candidate.toolsEnabled()).isFalse();
            assertThat(candidate.toolPolicy()).isEqualTo(AiRuntime.ToolPolicy.NONE);
        });
    }

    @Test
    void executesToolWorkflowWithModelAuthoredGuideAndVerbs() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "chain", true, "I’ll review the current release and component.",
                "Reviewing", "Reviewed", null, "Answering", "Answered", List.of()));
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("grounded answer"));
        when(recorder.completedDomainToolCallCount()).thenReturn(0L, 1L, 1L);

        AiRuntime.Result result;
        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.traceMetadata()).containsEntry("workflow", "chain")
                .containsEntry("active_verb", "Reviewing")
                .containsEntry("completed_verb", "Reviewed");
        verify(recorder).guide(eq("I’ll review the current release and component."), any());
        ArgumentCaptor<AiRuntime.Context> executed = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes).execute(eq("default"), executed.capture());
        assertThat(executed.getValue().toolsEnabled()).isTrue();
        assertThat(executed.getValue().streamVisibleContent()).isFalse();
        assertThat(executed.getValue().toolPolicy()).isEqualTo(AiRuntime.ToolPolicy.FULL);
    }

    @Test
    void retriesAToolRequiredWorkflowThatOnlyReturnsANarratedPromise() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", true, "I’ll inspect the context schemes.",
                "Inspecting", "Inspected", null, "Answering", "Answered", List.of()));
        when(runtimes.execute(eq("default"), any()))
                .thenReturn(new AiRuntime.Result("I'll find the right tool."))
                .thenReturn(new AiRuntime.Result("Found the current context schemes."));
        when(recorder.completedDomainToolCallCount()).thenReturn(0L, 0L, 1L, 1L);

        AiRuntime.Result result;
        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("Found the current context schemes.");
        ArgumentCaptor<AiRuntime.Context> calls = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes, times(2)).execute(eq("default"), calls.capture());
        assertThat(calls.getAllValues().get(1).history())
                .anySatisfy(message -> assertThat(message)
                        .isInstanceOfSatisfying(AssistantMessage.class,
                                assistant -> assertThat(assistant.getText())
                                        .isEqualTo("I'll find the right tool.")))
                .anySatisfy(message -> assertThat(message)
                        .isInstanceOfSatisfying(SystemMessage.class,
                                system -> assertThat(system.getText())
                                        .contains("completed no connectCenter domain tool call",
                                                "structured tool API")));
    }

    @Test
    void allowsTheSameRegisteredAgentForTwoDurableChildConversations() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned record precisely.", AiRuntime.ToolPolicy.READ_ONLY);
        when(catalog.require("evidence-researcher")).thenReturn(researcher);
        AiWorkflowPlan.Task first = new AiWorkflowPlan.Task(
                "Sync Purchase Order", researcher.id(), "Read Sync Purchase Order in release 10.13.",
                "I’ll review Sync Purchase Order.", "Reviewing", "Reviewed");
        AiWorkflowPlan.Task second = new AiWorkflowPlan.Task(
                "Get Purchase Order", researcher.id(), "Read Get Purchase Order in release 10.13.",
                "I’ll review Get Purchase Order.", "Reviewing", "Reviewed");
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, "I’ll review both BODs independently and compare them.",
                "Reviewing", "Reviewed", "I’ll compare the results and prepare the answer.",
                "Comparing", "Compared", List.of(first, second)));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childTwo = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(childOne.conversationId()).thenReturn("child-1");
        when(childTwo.conversationId()).thenReturn("child-2");
        when(root.forkParallelExecution(eq(researcher.id()), eq(first.instruction()), any()))
                .thenReturn(childOne);
        when(root.forkParallelExecution(eq(researcher.id()), eq(second.instruction()), any()))
                .thenReturn(childTwo);
        when(runtimes.execute(eq("default"), any())).thenAnswer(invocation -> {
            AiRuntime.Context candidate = invocation.getArgument(1);
            if (candidate.agentDepth() == 1) {
                return new AiRuntime.Result("child-1".equals(candidate.request().conversationId())
                        ? "sync evidence" : "get evidence");
            }
            return new AiRuntime.Result("comparison");
        });

        AiRuntime.Result result;
        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            result = manager.execute(context(root,
                    AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("comparison");
        assertThat(result.traceMetadata()).containsEntry("workflow", "parallel")
                .containsEntry("completed_verb", "Compared");
        verify(root, times(2)).forkParallelExecution(eq("evidence-researcher"), any(), any());
        verify(childOne).lifecycle(eq("parallel_task_started"), any(), any());
        verify(childOne).terminalLifecycle(eq("parallel_task_completed"), any(), any());
        verify(childTwo).lifecycle(eq("parallel_task_started"), any(), any());
        verify(childTwo).terminalLifecycle(eq("parallel_task_completed"), any(), any());
        verify(lead).lifecycle(eq("parallel_workflow_started"),
                eq("I’ll review both BODs independently and compare them."), any());
        verify(lead).lifecycle(eq("parallel_workflow_synthesizing"),
                eq("I’ll compare the results and prepare the answer."), any());
        verify(lead).terminalLifecycle(eq("parallel_workflow_completed"), eq("Compared."), any());
        verify(root).recordFanOutUsage(any(), eq("parallel"), any());

        ArgumentCaptor<AiRuntime.Context> calls = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes, atLeastOnce()).execute(eq("default"), calls.capture());
        List<AiRuntime.Context> children = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(children).hasSize(2);
        assertThat(children).extracting(candidate -> candidate.request().conversationId())
                .containsExactlyInAnyOrder("child-1", "child-2");
        assertThat(children).allSatisfy(candidate -> {
            assertThat(candidate.toolPolicy()).isEqualTo(AiRuntime.ToolPolicy.READ_ONLY);
            assertThat(candidate.streamVisibleContent()).isFalse();
            assertThat(candidate.history()).hasSize(1);
            assertThat(((SystemMessage) candidate.history().getFirst()).getText())
                    .contains("isolated workflow worker", "Research the assigned record precisely");
        });
        String synthesis = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 0).findFirst()
                .map(candidate -> ((SystemMessage) candidate.history().getLast()).getText())
                .orElseThrow();
        assertThat(synthesis).contains("sync evidence", "get evidence",
                "Sync Purchase Order", "Get Purchase Order");
    }

    @Test
    void keepsExplicitMultiAgentFanOutInSubagentConversations() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assignment.", AiRuntime.ToolPolicy.READ_ONLY);
        when(catalog.require(agent.id())).thenReturn(agent);
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Evidence", agent.id(), "Inspect the record.", null,
                "Inspecting", "Inspected");
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, "I’ll use multiple agents.", "Inspecting", "Inspected",
                "I’ll synthesize their findings.", "Synthesizing", "Synthesized", List.of(task)));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(child.conversationId()).thenReturn("subagent-1");
        when(root.forkSubagent(eq(agent.id()), eq(task.instruction()), any()))
                .thenReturn(child);
        when(runtimes.execute(eq("default"), any())).thenAnswer(invocation -> {
            AiRuntime.Context candidate = invocation.getArgument(1);
            return new AiRuntime.Result(candidate.agentDepth() == 1 ? "evidence" : "answer");
        });

        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            assertThat(manager.execute(context(root,
                    new AiMultiAgentOptions(true, 2, "balanced"), 0)).answer())
                    .isEqualTo("answer");
        }

        verify(root).forkSubagent(eq(agent.id()), eq(task.instruction()), any());
        verify(root, never()).forkParallelExecution(any(), any(), any());
        verify(child).lifecycle(eq("subagent_started"), any(), any());
        verify(child).terminalLifecycle(eq("subagent_completed"), any(), any());
        verify(lead).lifecycle(eq("multi_agent_started"), any(), any());
        verify(lead).terminalLifecycle(eq("multi_agent_completed"), any(), any());
        verify(root).recordFanOutUsage(any(), eq("multi_agent"), any());
    }

    @Test
    void chainRunsWorkersInOrderAndCarriesPriorEvidence() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition("general-purpose", "General purpose", "general",
                "Complete the assignment.", AiRuntime.ToolPolicy.READ_ONLY);
        when(catalog.require(agent.id())).thenReturn(agent);
        AiWorkflowPlan.Task first = new AiWorkflowPlan.Task(
                "Locate", agent.id(), "Locate the record.", null, "Locating", "Located");
        AiWorkflowPlan.Task second = new AiWorkflowPlan.Task(
                "Verify", agent.id(), "Verify the located record.", null, "Verifying", "Verified");
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "chain", true, "I’ll locate and verify the record.", "Checking", "Checked",
                "I’ll synthesize the results.", "Synthesizing", "Synthesized", List.of(first, second)));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childTwo = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(childOne.conversationId()).thenReturn("child-1");
        when(childTwo.conversationId()).thenReturn("child-2");
        when(root.forkSubagent(eq(agent.id()), any(), any()))
                .thenReturn(childOne)
                .thenReturn(childTwo);
        AtomicInteger workers = new AtomicInteger();
        when(runtimes.execute(eq("default"), any())).thenAnswer(invocation -> {
            AiRuntime.Context candidate = invocation.getArgument(1);
            if (candidate.agentDepth() == 0) return new AiRuntime.Result("final");
            return new AiRuntime.Result(workers.incrementAndGet() == 1 ? "located id 74" : "verified");
        });

        try (AiMultiAgentManager manager = manager(runtimes, planner, catalog)) {
            assertThat(manager.execute(context(root,
                    new AiMultiAgentOptions(true, 2, "balanced"), 0)).answer()).isEqualTo("final");
        }

        ArgumentCaptor<AiRuntime.Context> calls = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes, atLeastOnce()).execute(eq("default"), calls.capture());
        List<AiRuntime.Context> children = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(((SystemMessage) children.get(1).history().getFirst()).getText())
                .contains("Prior chain results", "located id 74");
    }

    @Test
    void rejectsNestedDelegation() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        try (AiMultiAgentManager manager = manager(runtimes, planner, mock(AiAgentCatalog.class))) {
            assertThatThrownBy(() -> manager.execute(context(mock(AiTrajectoryRecorder.class),
                    new AiMultiAgentOptions(true, 2, "balanced"), 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nested");
        }
        verify(planner, never()).plan(any());
    }

    private AiMultiAgentManager manager(AiRuntimeRegistry runtimes, AiWorkflowPlanner planner,
                                        AiAgentCatalog catalog) {
        return new AiMultiAgentManager(runtimes, planner, catalog, null, null,
                16, 8, Duration.ofSeconds(2));
    }

    private AiRuntime.Context context(AiTrajectoryRecorder recorder, AiMultiAgentOptions options,
                                      int depth) {
        ChatRequest request = new ChatRequest("Investigate", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "default",
                Map.of(), "ask", options);
        return new AiRuntime.Context(request, List.of(), new UserMessage("Investigate"),
                mock(ScoreUser.class), recorder, true, true, AiRuntime.ToolPolicy.FULL, depth);
    }
}
