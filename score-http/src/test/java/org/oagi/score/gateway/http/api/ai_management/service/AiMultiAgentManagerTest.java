package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowNode;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.service.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.service.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.workflow.DirectWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.Workflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMultiAgentManagerTest {

    @Test
    void executesStableKnowledgeWithoutToolsOrGuide() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", false, null, "Answering", "Answered",
                null, "Answering", "Answered", List.of()));
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("stable answer"));

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            assertThat(manager.execute(context(recorder, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("stable answer");
        }

        ArgumentCaptor<AiChatExecutor.Context> executed = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(executed.capture());
        assertThat(executed.getValue().toolsEnabled()).isFalse();
        assertThat(executed.getValue().toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
        verify(recorder, never()).guide(any(), any());
    }

    @Test
    void executesAForcedWorkerWorkflowWithoutGrantingUnneededTools() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "general-purpose", "General purpose", "general analysis",
                "Analyze the assignment.", AiChatExecutor.ToolPolicy.READ_ONLY);
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
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 1 ? "worker answer" : "final answer");
        });

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            assertThat(manager.execute(context(root, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("final answer");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(2)).execute(calls.capture());
        assertThat(calls.getAllValues()).allSatisfy(candidate -> {
            assertThat(candidate.toolsEnabled()).isFalse();
            assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
        });
    }

    @Test
    void isolatesTheWorkerAssignmentAndRetriesUntilARealDomainReadCompletes() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned records.", AiChatExecutor.ToolPolicy.READ_ONLY);
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Context evidence", researcher.id(),
                "Read the exact context scheme and category and return their stable IDs.",
                null, "Researching", "Researched");
        when(catalog.require(researcher.id())).thenReturn(researcher);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "orchestrator_workers", true, "I’ll verify the current records.",
                "Researching", "Researched", "I’ll complete the requested action if needed.",
                "Completing", "Completed", List.of(task)));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(child.conversationId()).thenReturn("child-1");
        when(root.forkSubagent(eq(researcher.id()), eq(task.instruction()), any()))
                .thenReturn(child);
        when(child.successfulDomainToolCallCount()).thenReturn(0L, 0L, 1L, 1L);
        AtomicInteger workerCalls = new AtomicInteger();
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 0) return new AiChatExecutor.Result("final answer");
            return new AiChatExecutor.Result(workerCalls.incrementAndGet() == 1
                    ? "I cannot create records because I am read-only."
                    : "Context Scheme ID 34; Context Category ID 44.");
        });
        ChatRequest request = new ChatRequest(
                "You didn't create the context scheme and category. Create them too.",
                "request-1", null, "conversation-1", null, List.of(), null,
                "model", "high", "ask",
                new AiMultiAgentOptions(true, 2, "balanced"), null, null);
        AiChatExecutor.Context context = new AiChatExecutor.Context(
                request, List.of(), new UserMessage(request.prompt()), mock(ScoreUser.class),
                root, true, true, AiChatExecutor.ToolPolicy.FULL, 0);

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            assertThat(manager.execute(context).answer()).isEqualTo("final answer");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(3)).execute(calls.capture());
        List<AiChatExecutor.Context> workers = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(workers).hasSize(2).allSatisfy(worker -> {
            assertThat(worker.userMessage().getText()).isEqualTo(
                    "WORKER_ASSIGNMENT\n" + task.instruction());
            assertThat(worker.userMessage().getText()).doesNotContain("Create them too");
        });
        assertThat(workers.getFirst().history()).anySatisfy(message ->
                assertThat(message.getText()).contains(
                        "INTERNAL_ORIGINAL_REQUEST_REFERENCE", "Create them too"));
        assertThat(workers.getLast().history())
                .anySatisfy(message -> assertThat(message)
                        .isInstanceOfSatisfying(AssistantMessage.class,
                                assistant -> assertThat(assistant.getText()).contains("read-only")))
                .anySatisfy(message -> assertThat(message)
                        .isInstanceOfSatisfying(SystemMessage.class,
                                system -> assertThat(system.getText()).contains(
                                        "toolSearchTool call only discovers schemas",
                                        "at least one relevant connectCenter get/list tool")));
        verify(child).terminalLifecycle(eq("subagent_completed"), any(), any());
    }

    @Test
    void failsAWorkerAfterItsRecoveryAlsoCompletesNoDomainRead() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned records.", AiChatExecutor.ToolPolicy.READ_ONLY);
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Context evidence", researcher.id(), "Read the current context schemes.",
                null, "Researching", "Researched");
        when(catalog.require(researcher.id())).thenReturn(researcher);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "orchestrator_workers", true, "I’ll verify the current records.",
                "Researching", "Researched", "I’ll synthesize verified evidence.",
                "Synthesizing", "Synthesized", List.of(task)));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(child.conversationId()).thenReturn("child-1");
        when(root.forkSubagent(eq(researcher.id()), eq(task.instruction()), any()))
                .thenReturn(child);
        when(child.successfulDomainToolCallCount()).thenReturn(0L);
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("I’ll look that up."));

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            assertThatThrownBy(() -> manager.execute(
                    context(root, new AiMultiAgentOptions(true, 2, "balanced"), 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("All delegated agents failed.");
        }

        verify(executor, times(2)).execute(any());
        verify(child).terminalLifecycle(eq("subagent_failed"), any(), any());
        verify(child, never()).terminalLifecycle(eq("subagent_completed"), any(), any());
    }

    @Test
    void executesToolWorkflowWithModelAuthoredGuideAndVerbs() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "chain", true, "I’ll review the current release and component.",
                "Reviewing", "Reviewed", null, "Answering", "Answered", List.of()));
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("grounded answer"));
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.traceMetadata()).containsEntry("workflow", "chain")
                .containsEntry("active_verb", "Reviewing")
                .containsEntry("completed_verb", "Reviewed");
        verify(recorder).guide(eq("I’ll review the current release and component."), any());
        ArgumentCaptor<AiChatExecutor.Context> executed = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(executed.capture());
        assertThat(executed.getValue().toolsEnabled()).isTrue();
        assertThat(executed.getValue().streamVisibleContent()).isFalse();
        assertThat(executed.getValue().toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.FULL);
    }

    @Test
    void retriesAToolRequiredWorkflowThatOnlyReturnsANarratedPromise() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", true, "I’ll inspect the context schemes.",
                "Inspecting", "Inspected", null, "Answering", "Answered", List.of()));
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("I'll find the right tool."))
                .thenReturn(new AiChatExecutor.Result("Found the current context schemes."));
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L, 0L, 1L, 1L);

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("Found the current context schemes.");
        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(2)).execute(calls.capture());
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
    void failsAToolRequiredWorkflowAfterItsRecoveryRemainsUngrounded() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", true, "I’ll inspect the context schemes.",
                "Inspecting", "Inspected", null, "Answering", "Answered", List.of()));
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("I'll find the right tool."))
                .thenReturn(new AiChatExecutor.Result("I still have no current evidence."));
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L);

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            assertThatThrownBy(() -> manager.execute(
                    context(recorder, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no successful connectCenter domain tool call");
        }

        verify(executor, times(2)).execute(any());
        verify(recorder).lifecycle(eq("required_tool_unfulfilled"), any(),
                eq(Map.of("status", "failed", "recovery_attempts", 1)));
    }

    @Test
    void allowsTheSameRegisteredAgentForTwoDurableChildConversations() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned record precisely.", AiChatExecutor.ToolPolicy.READ_ONLY);
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
        when(childOne.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(childTwo.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 1) {
                return new AiChatExecutor.Result("child-1".equals(candidate.request().conversationId())
                        ? "sync evidence" : "get evidence");
            }
            return new AiChatExecutor.Result("comparison");
        });

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
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

        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, atLeastOnce()).execute(calls.capture());
        List<AiChatExecutor.Context> children = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(children).hasSize(2);
        assertThat(children).extracting(candidate -> candidate.request().conversationId())
                .containsExactlyInAnyOrder("child-1", "child-2");
        assertThat(children).allSatisfy(candidate -> {
            assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY);
            assertThat(candidate.streamVisibleContent()).isFalse();
            assertThat(candidate.history()).hasSize(2);
            assertThat(((SystemMessage) candidate.history().getFirst()).getText())
                    .contains("isolated workflow worker", "Research the assigned record precisely");
            assertThat(candidate.history().get(1)).isInstanceOfSatisfying(UserMessage.class,
                    reference -> assertThat(reference.getText())
                            .contains("INTERNAL_ORIGINAL_REQUEST_REFERENCE"));
            assertThat(candidate.userMessage().getText()).startsWith("WORKER_ASSIGNMENT")
                    .doesNotContain("Investigate\n");
        });
        // Worker answers are untrusted evidence: they re-enter the lead synthesis
        // call as user-role reference data, never with system-role authority.
        Object synthesis = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 0).findFirst()
                .map(candidate -> candidate.history().getLast())
                .orElseThrow();
        assertThat(synthesis).isInstanceOfSatisfying(UserMessage.class,
                reference -> assertThat(reference.getText()).contains(
                        "sync evidence", "get evidence",
                        "Sync Purchase Order", "Get Purchase Order"));
    }

    @Test
    void harvestsAParallelWorkerThatCompletedBeforeTheDeadlineWasExhausted() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned record precisely.", AiChatExecutor.ToolPolicy.READ_ONLY);
        when(catalog.require(researcher.id())).thenReturn(researcher);
        AiWorkflowPlan.Task blocked = new AiWorkflowPlan.Task(
                "Blocked record", researcher.id(), "Read the first record.",
                null, "Reading", "Read");
        AiWorkflowPlan.Task fast = new AiWorkflowPlan.Task(
                "Fast record", researcher.id(), "Read the second record.",
                null, "Reading", "Read");
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, "I’ll review both records independently.",
                "Reviewing", "Reviewed", "I’ll combine the results.",
                "Combining", "Combined", List.of(blocked, fast)));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childTwo = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(childOne.conversationId()).thenReturn("child-1");
        when(childTwo.conversationId()).thenReturn("child-2");
        when(root.forkParallelExecution(eq(researcher.id()), eq(blocked.instruction()), any()))
                .thenReturn(childOne);
        when(root.forkParallelExecution(eq(researcher.id()), eq(fast.instruction()), any()))
                .thenReturn(childTwo);
        when(childTwo.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 1) {
                if ("child-1".equals(candidate.request().conversationId())) {
                    try {
                        neverReleased.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("cancelled", failure);
                    }
                    return new AiChatExecutor.Result("late evidence");
                }
                return new AiChatExecutor.Result("fast evidence");
            }
            return new AiChatExecutor.Result("combined answer");
        });

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = new AiMultiAgentManager(
                executor, planner, catalog, null, null, 16, 8, Duration.ofMillis(300))) {
            result = manager.execute(context(root, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("combined answer");
        assertThat(result.traceMetadata())
                .containsEntry("completed_agents", 1)
                .containsEntry("failed_agents", 1);
        verify(childTwo).terminalLifecycle(eq("parallel_task_completed"), any(), any());
        verify(childOne).terminalLifecycle(eq("parallel_task_failed"), any(), any());
    }

    @Test
    void keepsExplicitMultiAgentFanOutInSubagentConversations() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assignment.", AiChatExecutor.ToolPolicy.READ_ONLY);
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
        when(child.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 1 ? "evidence" : "answer");
        });

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
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
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition("general-purpose", "General purpose", "general",
                "Complete the assignment.", AiChatExecutor.ToolPolicy.READ_ONLY);
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
        when(childOne.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(childTwo.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        AtomicInteger workers = new AtomicInteger();
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 0) return new AiChatExecutor.Result("final");
            return new AiChatExecutor.Result(workers.incrementAndGet() == 1 ? "located id 74" : "verified");
        });

        try (AiMultiAgentManager manager = manager(executor, planner, catalog)) {
            assertThat(manager.execute(context(root,
                    new AiMultiAgentOptions(true, 2, "balanced"), 0)).answer()).isEqualTo("final");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, atLeastOnce()).execute(calls.capture());
        List<AiChatExecutor.Context> children = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        // Prior chain evidence reaches the next worker as user-role reference
        // data; the worker's system prompt itself stays free of model output.
        assertThat(((SystemMessage) children.get(1).history().getFirst()).getText())
                .doesNotContain("located id 74");
        assertThat(children.get(1).history().getLast())
                .isInstanceOfSatisfying(UserMessage.class,
                        reference -> assertThat(reference.getText()).contains(
                                "INTERNAL_WORKFLOW_UPSTREAM",
                                "prior chain results", "located id 74"));
    }

    @Test
    void rejectsNestedDelegation() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        try (AiMultiAgentManager manager = manager(executor, planner, mock(AiAgentCatalog.class))) {
            assertThatThrownBy(() -> manager.execute(context(mock(AiTrajectoryRecorder.class),
                    new AiMultiAgentOptions(true, 2, "balanced"), 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nested");
        }
        verify(planner, never()).plan(any());
    }

    @Test
    void replansWithEvaluatorFeedbackUntilTheResultIsComplete() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiWorkflowEvaluator evaluator = mock(AiWorkflowEvaluator.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiWorkflowPlan direct = new AiWorkflowPlan(
                "direct", false, null, "Answering", "Answered",
                null, "Answering", "Answered", List.of());
        when(planner.plan(any(), anyList())).thenReturn(direct);
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("first attempt"))
                .thenReturn(new AiChatExecutor.Result("verified answer"));
        when(evaluator.evaluate(any(), eq(direct), any(), eq(1), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.CONTINUE,
                        "The answer lacks verification.", "Verify against current evidence."));
        when(evaluator.evaluate(any(), eq(direct), any(), eq(2), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.COMPLETE,
                        null, null));

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = new AiMultiAgentManager(
                executor, planner, evaluator, catalog, null, null,
                16, 8, Duration.ofSeconds(2))) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("verified answer");
        assertThat(result.traceMetadata())
                .containsEntry("workflow_iterations", 2)
                .containsEntry("evaluation_status", "complete");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiWorkflowFeedback>> feedback = ArgumentCaptor.forClass(List.class);
        verify(planner, times(2)).plan(any(), feedback.capture());
        assertThat(feedback.getAllValues().getFirst()).isEmpty();
        assertThat(feedback.getAllValues().get(1)).singleElement().satisfies(item ->
                assertThat(item.nextObjective()).isEqualTo("Verify against current evidence."));
    }

    @Test
    void reportsTheIterationLimitWhenWorkStillRemains() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiWorkflowEvaluator evaluator = mock(AiWorkflowEvaluator.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiWorkflowPlan direct = new AiWorkflowPlan(
                "direct", false, null, "Answering", "Answered",
                null, "Answering", "Answered", List.of());
        when(planner.plan(any(), anyList())).thenReturn(direct);
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("incomplete"));
        when(evaluator.evaluate(any(), eq(direct), any(), anyInt(), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.CONTINUE,
                        "Evidence is missing.", "Retrieve the evidence."));

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = new AiMultiAgentManager(
                executor, planner, evaluator, mock(AiAgentCatalog.class), null, null,
                16, 8, Duration.ofSeconds(2))) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.traceMetadata())
                .containsEntry("workflow_iterations", 3)
                .containsEntry("evaluation_status", "iteration_limit")
                .containsEntry("remaining_objective", "Retrieve the evidence.");
        verify(planner, times(3)).plan(any(), anyList());
        // A CONTINUE verdict on the final iteration runs nothing further, so the
        // user must not read a continuation promise; earlier iterations narrate
        // after the dedup window reopens for their replanning round.
        verify(recorder, times(2)).guide(
                startsWith("Continuing with the remaining objective"), any());
        verify(recorder, times(2)).resetGuideDeduplication();
    }

    @Test
    void forcesReadOnlyAdmittedSynthesisForAContainerNestedInAParallelBranch() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.fork(any())).thenAnswer(ignored -> groundedBranchRecorder());
        AiWorkflowNode inner = new AiWorkflowNode("inner", "parallel", true, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(directLeaf("inner-a"), directLeaf("inner-b")), Map.of());
        AiWorkflowNode root = new AiWorkflowNode("root", "parallel", true, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(inner, directLeaf("outer-c")), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root));
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 0 ? "final" : "evidence");
        });

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = manager(executor, planner, mock(AiAgentCatalog.class))) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("final");
        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(5)).execute(calls.capture());
        // The three concurrent leaves AND the nested container's synthesis all
        // execute read-only at depth 1: a synthesis running while outer siblings
        // are still executing is not the exclusive lead. Only the sequential
        // root synthesis keeps the mutation-capable policy.
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList())
                .hasSize(4)
                .allSatisfy(candidate -> assertThat(candidate.toolPolicy())
                        .isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY));
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 0).toList())
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.toolPolicy())
                        .isEqualTo(AiChatExecutor.ToolPolicy.FULL));
    }

    @Test
    void retriesToolRequiredConcurrentDirectLeavesUntilTheyReadDomainData() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.fork(any())).thenAnswer(ignored -> recoveredBranchRecorder());
        AiWorkflowNode root = new AiWorkflowNode("root", "parallel", true, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(directLeaf("first"), directLeaf("second")), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root));
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 0
                    ? "final answer" : "branch evidence");
        });

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = manager(executor, planner, mock(AiAgentCatalog.class))) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("final answer");
        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(5)).execute(calls.capture());
        List<AiChatExecutor.Context> branchCalls = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(branchCalls).hasSize(4).allSatisfy(candidate ->
                assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY));
        assertThat(branchCalls.stream().filter(candidate -> candidate.history().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .anyMatch(text -> text.contains("INTERNAL_WORKFLOW_RECOVERY"))).toList())
                .hasSize(2);
    }

    @Test
    void failsToolRequiredConcurrentDirectLeavesAfterRecoveryExhaustion() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.fork(any())).thenAnswer(ignored -> mock(AiTrajectoryRecorder.class));
        AiWorkflowNode root = new AiWorkflowNode("root", "parallel", true, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(directLeaf("first"), directLeaf("second")), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root));
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("No current evidence."));

        try (AiMultiAgentManager manager = manager(executor, planner, mock(AiAgentCatalog.class))) {
            assertThatThrownBy(() -> manager.execute(
                    context(recorder, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("All parallel workflow branches failed.");
        }

        verify(executor, times(4)).execute(any());
    }

    private AiWorkflowNode directLeaf(String id) {
        return new AiWorkflowNode(id, "direct", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", null, null, List.of(), Map.of());
    }

    private AiTrajectoryRecorder groundedBranchRecorder() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L, 1L);
        return recorder;
    }

    private AiTrajectoryRecorder recoveredBranchRecorder() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L, 0L, 1L);
        return recorder;
    }

    @Test
    void usesARegisteredWorkflowCompilerExtensionForComposedPlans() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiWorkflowNode root = new AiWorkflowNode("root", "custom_direct", false, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "custom_direct", false, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root));
        AiWorkflowCompiler.WorkflowNodeCompiler extension =
                new AiWorkflowCompiler.WorkflowNodeCompiler() {
                    @Override
                    public String workflowType() {
                        return "custom_direct";
                    }

                    @Override
                    public Workflow compile(AiWorkflowNode node,
                                            AiWorkflowCompiler.CompilationContext context) {
                        return new DirectWorkflow(node.id(), ignored -> WorkflowResult.success(
                                node.id(), "extension answer", Map.of(), List.of()));
                    }
                };

        AiChatExecutor.Result result;
        try (AiMultiAgentManager manager = new AiMultiAgentManager(
                executor, planner, null, mock(AiAgentCatalog.class), null, null,
                16, 8, Duration.ofSeconds(2), List.of(extension))) {
            result = manager.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("extension answer");
        assertThat(result.traceMetadata()).containsEntry("workflow", "custom_direct");
        verify(executor, never()).execute(any());
    }

    private AiMultiAgentManager manager(AiChatExecutor executor, AiWorkflowPlanner planner,
                                        AiAgentCatalog catalog) {
        return new AiMultiAgentManager(executor, planner, catalog, null, null,
                16, 8, Duration.ofSeconds(2));
    }

    private AiChatExecutor.Context context(AiTrajectoryRecorder recorder, AiMultiAgentOptions options,
                                      int depth) {
        ChatRequest request = new ChatRequest("Investigate", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask",
                options, null, null);
        return new AiChatExecutor.Context(request, List.of(), new UserMessage("Investigate"),
                mock(ScoreUser.class), recorder, true, true, AiChatExecutor.ToolPolicy.FULL, depth);
    }
}
