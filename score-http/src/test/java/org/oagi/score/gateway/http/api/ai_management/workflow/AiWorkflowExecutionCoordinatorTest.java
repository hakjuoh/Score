package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationConfirmationDecisionResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowSynthesizerAgent;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationDecision;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowNode;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowCompiler;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowEvaluator;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowPlanner;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkflowExecutionCoordinatorTest {

    @Test
    void executesStableKnowledgeWithoutToolsOrGuide() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", false, null, "Answering", "Answered",
                null, "Answering", "Answered", List.of()));
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("stable answer"));

        AiChatExecutor.Context root = context(recorder, AiMultiAgentOptions.single(), 0)
                .withAgentIdentity("external-root-agent",
                        org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(root).answer())
                    .isEqualTo("stable answer");
        }

        ArgumentCaptor<AiChatExecutor.Context> executed = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(executed.capture());
        assertThat(executed.getValue().toolsEnabled()).isFalse();
        assertThat(executed.getValue().toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
        assertThat(executed.getValue().agentId()).isEqualTo("external-root-agent");
        assertThat(executed.getValue().executionPurpose())
                .isEqualTo(org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        verify(recorder, never()).guide(any(), any());
    }

    @Test
    void executesAForcedWorkerWorkflowWithoutGrantingUnneededTools() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "general-purpose", "General purpose", "general analysis",
                "Analyze the assignment.");
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
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
            return candidate.agentDepth() == 1
                    ? new AiChatExecutor.Result("worker answer")
                    : new AiChatExecutor.Result("final answer", Map.of(
                            "agentId", "external-root-agent"));
        });

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(root, AiMultiAgentOptions.single(), 0)
                    .withAgentIdentity("external-root-agent",
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE));
        }

        assertThat(result.answer()).isEqualTo("final answer");
        assertThat(result.traceMetadata()).containsEntry("agentId", "external-root-agent");
        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(2)).execute(calls.capture());
        assertThat(calls.getAllValues()).allSatisfy(candidate -> {
            assertThat(candidate.toolsEnabled()).isFalse();
            assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
        });
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 0).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.agentId()).isEqualTo("external-root-agent");
                    assertThat(candidate.executionPurpose()).isEqualTo(
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
                });
    }

    @Test
    void isolatesTheWorkerAssignmentAndRetriesUntilARealDomainReadCompletes() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned records.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Context evidence", researcher.id(),
                "Read the exact context scheme and category and return their stable IDs.",
                null, "Researching", "Researched");
        when(catalog.requireWorker(researcher.id())).thenReturn(researcher);
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

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(context).answer()).isEqualTo("final answer");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(3)).execute(calls.capture());
        List<AiChatExecutor.Context> workers = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(workers).hasSize(2).allSatisfy(worker -> {
            assertThat(worker.userMessage().getText()).isEqualTo(
                    "WORKER_ASSIGNMENT\n" + task.instruction());
            assertThat(worker.userMessage().getText()).doesNotContain("Create them too");
            assertThat(worker.agentId()).isEqualTo(researcher.id());
            assertThat(worker.executionPurpose()).isEqualTo(
                    org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER);
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
                "Research the assigned records.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Context evidence", researcher.id(), "Read the current context schemes.",
                null, "Researching", "Researched");
        when(catalog.requireWorker(researcher.id())).thenReturn(researcher);
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

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThatThrownBy(() -> coordinator.execute(
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
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
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
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
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
    void doesNotReplayAToolRequiredAssignmentAfterTheUserDeniedItsApprovalBarrier() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", true, "I’ll apply the requested update.",
                "Updating", "Updated", null, "Answering", "Answered", List.of()));
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result(
                "The requested update was denied and was not executed.", Map.of(
                "approvalBarrierResolved", true,
                "approvalBarrierCount", 1,
                "approvedMutationCount", 0,
                "deniedMutationCount", 1)));
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L);

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).contains("denied", "not executed");
        assertThat(result.traceMetadata())
                .containsEntry("approvalBarrierResolved", true)
                .containsEntry("deniedMutationCount", 1);
        verify(executor, times(1)).execute(any());
        verify(recorder, never()).lifecycle(eq("required_tool_unfulfilled"), any(), any());
    }

    @Test
    void doesNotReplayAFlatFullAccessChildAfterItsApprovalWasDenied() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition writer = new AiAgentDefinition(
                "record-writer", "Record writer", "controlled updates",
                "Apply the assigned update.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Update record", writer.id(), "Update record 42.",
                null, "Updating", "Updated", AiWorkflowPlan.ToolAccess.FULL);
        when(catalog.requireWorker(writer.id())).thenReturn(writer);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "orchestrator_workers", true, "I’ll delegate the update.",
                "Updating", "Updated", "I’ll summarize the outcome.",
                "Summarizing", "Summarized", List.of(task)));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(writer.id()), eq(task.instruction()), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("child-1");
        when(child.successfulDomainToolCallCount()).thenReturn(0L);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return candidate.agentDepth() == 1
                    ? deniedApprovalResult()
                    : new AiChatExecutor.Result("The delegated update was denied.");
        });

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(context(root,
                    new AiMultiAgentOptions(true, 2, "balanced"), 0)).answer())
                    .contains("denied");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls = ArgumentCaptor.forClass(
                AiChatExecutor.Context.class);
        verify(executor, times(2)).execute(calls.capture());
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.approvalScope().parallel()).isFalse();
                    assertThat(candidate.approvalScope().participantId()).isEqualTo("child-1");
                });
    }

    @Test
    void doesNotReplayAComposedFullAccessWorkerAfterItsApprovalWasDenied() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition writer = new AiAgentDefinition(
                "record-writer", "Record writer", "controlled updates",
                "Apply the assigned update.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Update record", writer.id(), "Update record 42.",
                null, "Updating", "Updated", AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "writer-node", "direct", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", task, null, List.of(), Map.of());
        when(catalog.requireWorker(writer.id())).thenReturn(writer);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", List.of(task), rootNode));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(writer.id()), eq(task.instruction()), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("child-composed");
        when(child.successfulDomainToolCallCount()).thenReturn(0L);
        when(executor.execute(any())).thenReturn(deniedApprovalResult());

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(root, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).contains("denied", "not executed");
        verify(executor, times(1)).execute(any());
        verify(child).terminalLifecycle(eq("subagent_completed"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesTheCompleteComposedWorkerPlanAndStableWorkerOwnershipBeforeExecution() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned records.");
        AiAgentDefinition reviewer = new AiAgentDefinition(
                "critical-reviewer", "Critical reviewer", "adversarial review",
                "Review the supplied evidence.");
        AiWorkflowPlan.Task research = new AiWorkflowPlan.Task(
                "Find extenders", researcher.id(), "Find every extending ACC.",
                "Listing every extending ACC.", "Searching", "Searched",
                AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan.Task review = new AiWorkflowPlan.Task(
                "Review conflicts", reviewer.id(), "Review every conflict.",
                "Reviewing every conflict.", "Reviewing", "Reviewed",
                AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowNode researchNode = new AiWorkflowNode(
                "find-extenders", "direct", false, research.guideMessage(),
                research.activeVerb(), research.completedVerb(), null,
                "Summarizing", "Summarized", research, null, List.of(), Map.of());
        AiWorkflowNode reviewNode = new AiWorkflowNode(
                "conflict-review", "direct", false, review.guideMessage(),
                review.activeVerb(), review.completedVerb(), null,
                "Summarizing", "Summarized", review, null, List.of(), Map.of());
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "conflict-chain", "chain", false, null, "Checking", "Checked",
                null, "Summarizing", "Summarized", null, null,
                List.of(researchNode, reviewNode), Map.of());
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "chain", false, "Checking every extender and reviewing conflicts.",
                "Checking", "Checked", null, "Summarizing", "Summarized",
                List.of(research, review), rootNode);
        when(planner.plan(any())).thenReturn(plan);
        when(catalog.requireWorker(researcher.id())).thenReturn(researcher);
        when(catalog.requireWorker(reviewer.id())).thenReturn(reviewer);

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder researchRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder reviewRecorder = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(researcher.id()), eq(research.instruction()), any()))
                .thenReturn(researchRecorder);
        when(root.forkSubagent(eq(reviewer.id()), eq(review.instruction()), any()))
                .thenReturn(reviewRecorder);
        when(researchRecorder.conversationId()).thenReturn("research-child");
        when(reviewRecorder.conversationId()).thenReturn("review-child");
        when(executor.execute(any())).thenReturn(
                new AiChatExecutor.Result("research evidence"),
                new AiChatExecutor.Result("reviewed evidence"));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(context(root,
                    new AiMultiAgentOptions(true, 2, "balanced"), 0)).answer())
                    .isEqualTo("reviewed evidence");
        }

        ArgumentCaptor<Map<String, Object>> leadNamespace =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(root).fork(leadNamespace.capture());
        assertThat(leadNamespace.getValue())
                .containsEntry("fanout_id", "request-1:composed")
                .containsEntry("node_id", "request-1:composed:lead")
                .containsEntry("execution_scope", "lead")
                .containsEntry("max_agents", 2);

        ArgumentCaptor<Map<String, Object>> workerNamespaces =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(root, times(2)).forkSubagent(any(), any(), workerNamespaces.capture());
        assertThat(workerNamespaces.getAllValues()).allSatisfy(namespace -> assertThat(namespace)
                .containsEntry("fanout_id", "request-1:composed")
                .containsEntry("parent_node_id", "request-1:composed:lead")
                .containsEntry("execution_scope", "worker")
                .containsEntry("execution_kind", "multi_agent"));
        assertThat(workerNamespaces.getAllValues())
                .extracting(namespace -> namespace.get("node_id"))
                .containsExactly("request-1:composed:worker:find-extenders",
                        "request-1:composed:worker:conflict-review");

        ArgumentCaptor<Map<String, Object>> leadStarted =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(lead).lifecycle(eq("multi_agent_started"), eq(plan.guideMessage()),
                leadStarted.capture());
        assertThat(leadStarted.getValue())
                .containsEntry("agent_count", 2)
                .containsEntry("active_verb", "Checking")
                .containsEntry("completed_verb", "Checked");
        verify(lead).terminalLifecycle(eq("multi_agent_completed"), eq("Checked."), any());
        verify(researchRecorder).lifecycle(eq("subagent_planned"), any(), any());
        verify(reviewRecorder).lifecycle(eq("subagent_planned"), any(), any());
        var lifecycleOrder = inOrder(lead, researchRecorder, reviewRecorder);
        lifecycleOrder.verify(lead).lifecycle(eq("multi_agent_started"), any(), any());
        lifecycleOrder.verify(researchRecorder).lifecycle(eq("subagent_planned"), any(), any());
        lifecycleOrder.verify(reviewRecorder).lifecycle(eq("subagent_planned"), any(), any());
        lifecycleOrder.verify(researchRecorder).lifecycle(eq("subagent_started"), any(), any());
        verify(researchRecorder, never()).guide(any(), any());
        verify(reviewRecorder, never()).guide(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void plansOnlyTheSelectedRoutingWorkers() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition selectedAgent = new AiAgentDefinition(
                "selected", "Selected", "selected route", "Execute the selected route.");
        AiAgentDefinition ignoredAgent = new AiAgentDefinition(
                "ignored", "Ignored", "ignored route", "Do not execute this route.");
        AiWorkflowPlan.Task selectedTask = new AiWorkflowPlan.Task(
                "Selected task", selectedAgent.id(), "Run selected.", null,
                "Selecting", "Selected");
        AiWorkflowPlan.Task ignoredTask = new AiWorkflowPlan.Task(
                "Ignored task", ignoredAgent.id(), "Run ignored.", null,
                "Ignoring", "Ignored");
        AiWorkflowNode selected = new AiWorkflowNode(
                "selected-node", "direct", false, null, "Selecting", "Selected",
                null, "Summarizing", "Summarized", selectedTask, null, List.of(), Map.of());
        AiWorkflowNode ignored = new AiWorkflowNode(
                "ignored-node", "direct", false, null, "Ignoring", "Ignored",
                null, "Summarizing", "Summarized", ignoredTask, null, List.of(), Map.of());
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "router", "routing", false, null, "Routing", "Routed",
                null, "Summarizing", "Summarized", null, "selected",
                List.of(), Map.of("selected", selected, "ignored", ignored));
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "routing", false, null, "Routing", "Routed",
                null, "Summarizing", "Summarized",
                List.of(selectedTask, ignoredTask), rootNode);
        when(planner.plan(any())).thenReturn(plan);
        when(catalog.requireWorker(selectedAgent.id())).thenReturn(selectedAgent);
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(selectedAgent.id()), any(), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("selected-child");
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("selected result"));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(context(root, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("selected result");
        }

        verify(catalog, never()).requireWorker(ignoredAgent.id());
        verify(root, times(1)).forkSubagent(any(), any(), any());
        ArgumentCaptor<Map<String, Object>> started =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(lead).lifecycle(eq("multi_agent_started"), any(), started.capture());
        assertThat(started.getValue()).containsEntry("agent_count", 1);
    }

    @Test
    void recordsComposedCancellationAsTerminalCancellation() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "researcher", "Researcher", "research", "Research the request.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Research", agent.id(), "Research now.", null, "Researching", "Researched");
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "research", "direct", false, null, "Researching", "Researched",
                null, "Summarizing", "Summarized", task, null, List.of(), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", false, null, "Researching", "Researched",
                null, "Summarizing", "Summarized", List.of(task), rootNode));
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(agent.id()), any(), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("research-child");
        when(executor.execute(any())).thenThrow(new CancellationException("cancelled"));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(root, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(CancellationException.class);
        }

        verify(child).terminalLifecycle(eq("subagent_cancelled"), any(), any());
        verify(child, never()).terminalLifecycle(eq("subagent_failed"), any(), any());
        verify(lead).terminalLifecycle(eq("multi_agent_cancelled"), any(), any());
        verify(lead, never()).terminalLifecycle(eq("multi_agent_failed"), any(), any());
    }

    @Test
    void cancelsTheComposedLeadWhenOneParallelBranchIsCancelled() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition cancelledAgent = new AiAgentDefinition(
                "cancelled-agent", "Cancelled", "cancelled branch", "Run the first branch.");
        AiAgentDefinition completedAgent = new AiAgentDefinition(
                "completed-agent", "Completed", "completed branch", "Run the second branch.");
        AiWorkflowPlan.Task cancelledTask = new AiWorkflowPlan.Task(
                "Cancelled branch", cancelledAgent.id(), "Run first.", null,
                "Working", "Completed", AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan.Task completedTask = new AiWorkflowPlan.Task(
                "Completed branch", completedAgent.id(), "Run second.", null,
                "Working", "Completed", AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = parallelComposedPlan(cancelledTask, completedTask);
        when(planner.plan(any())).thenReturn(plan);
        when(catalog.requireWorker(cancelledAgent.id())).thenReturn(cancelledAgent);
        when(catalog.requireWorker(completedAgent.id())).thenReturn(completedAgent);
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder cancelledChild = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder completedChild = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(cancelledAgent.id()), any(), any()))
                .thenReturn(cancelledChild);
        when(root.forkSubagent(eq(completedAgent.id()), any(), any()))
                .thenReturn(completedChild);
        when(cancelledChild.conversationId()).thenReturn("cancelled-child");
        when(completedChild.conversationId()).thenReturn("completed-child");
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (cancelledAgent.id().equals(candidate.agentId())) {
                throw new CancellationException("branch cancelled");
            }
            return new AiChatExecutor.Result("completed evidence");
        });

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(root, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(CancellationException.class);
        }

        verify(executor, times(2)).execute(any());
        verify(cancelledChild).terminalLifecycle(eq("subagent_cancelled"), any(), any());
        verify(completedChild).terminalLifecycle(eq("subagent_completed"), any(), any());
        verify(lead).terminalLifecycle(eq("multi_agent_cancelled"), any(), any());
        verify(lead, never()).terminalLifecycle(eq("multi_agent_completed"), any(), any());
        verify(lead, never()).terminalLifecycle(eq("multi_agent_failed"), any(), any());
    }

    @Test
    void cancelsTheComposedLeadWhenEveryParallelBranchIsCancelled() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition firstAgent = new AiAgentDefinition(
                "first-agent", "First", "first branch", "Run the first branch.");
        AiAgentDefinition secondAgent = new AiAgentDefinition(
                "second-agent", "Second", "second branch", "Run the second branch.");
        AiWorkflowPlan.Task firstTask = new AiWorkflowPlan.Task(
                "First branch", firstAgent.id(), "Run first.", null,
                "Working", "Completed", AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan.Task secondTask = new AiWorkflowPlan.Task(
                "Second branch", secondAgent.id(), "Run second.", null,
                "Working", "Completed", AiWorkflowPlan.ToolAccess.NONE);
        when(planner.plan(any())).thenReturn(parallelComposedPlan(firstTask, secondTask));
        when(catalog.requireWorker(firstAgent.id())).thenReturn(firstAgent);
        when(catalog.requireWorker(secondAgent.id())).thenReturn(secondAgent);
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder firstChild = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder secondChild = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(firstAgent.id()), any(), any())).thenReturn(firstChild);
        when(root.forkSubagent(eq(secondAgent.id()), any(), any())).thenReturn(secondChild);
        when(firstChild.conversationId()).thenReturn("first-child");
        when(secondChild.conversationId()).thenReturn("second-child");
        when(executor.execute(any())).thenThrow(new CancellationException("branch cancelled"));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(root, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(CancellationException.class);
        }

        verify(executor, times(2)).execute(any());
        verify(firstChild).terminalLifecycle(eq("subagent_cancelled"), any(), any());
        verify(secondChild).terminalLifecycle(eq("subagent_cancelled"), any(), any());
        verify(lead).terminalLifecycle(eq("multi_agent_cancelled"), any(), any());
        verify(lead, never()).terminalLifecycle(eq("multi_agent_completed"), any(), any());
        verify(lead, never()).terminalLifecycle(eq("multi_agent_failed"), any(), any());
    }

    @Test
    void recordsOneLeadCancellationWhenASequentialDirectLeafHitsTheStopFence() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "researcher", "Researcher", "research", "Research the request.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Research", agent.id(), "Research now.", null,
                "Researching", "Researched", AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowNode worker = new AiWorkflowNode(
                "research", "direct", false, null, "Researching", "Researched",
                null, "Synthesizing", "Synthesized", task, null, List.of(), Map.of());
        AiWorkflowNode direct = new AiWorkflowNode(
                "summarize", "direct", false, null, "Summarizing", "Summarized",
                null, "Synthesizing", "Synthesized", null, null, List.of(), Map.of());
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "root", "chain", false, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", null, null,
                List.of(worker, direct), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "chain", false, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(task), rootNode));
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
        when(requests.shouldDiscardResult("request-1")).thenReturn(false, false, true);
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(agent.id()), any(), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("research-child");
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("evidence"));

        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, null, catalog, null, requests,
                16, 8, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(root, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(CancellationException.class);
        }

        verify(child).terminalLifecycle(eq("subagent_completed"), any(), any());
        verify(lead, times(1)).terminalLifecycle(eq("multi_agent_cancelled"), any(), any());
        verify(root, never()).terminalLifecycle(eq("multi_agent_cancelled"), any(), any());
    }

    @Test
    void recordsComposedFailureAndSettlesEveryPlannedWorker() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "researcher", "Researcher", "research", "Research the request.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Research", agent.id(), "Research now.", null, "Researching", "Researched");
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "research", "direct", false, null, "Researching", "Researched",
                null, "Summarizing", "Summarized", task, null, List.of(), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "direct", false, null, "Researching", "Researched",
                null, "Summarizing", "Summarized", List.of(task), rootNode));
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(agent.id()), any(), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("research-child");
        when(executor.execute(any())).thenThrow(new IllegalStateException("provider failed"));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(root, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(IllegalStateException.class);
        }

        verify(child).terminalLifecycle(eq("subagent_failed"), any(), any());
        verify(lead).terminalLifecycle(eq("multi_agent_failed"), any(), any());
    }

    @Test
    void mixedComposedWorkflowDoesNotForceToolsOnAnExplicitNoneWorker() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition analyst = new AiAgentDefinition(
                "analyst", "Analyst", "offline analysis", "Analyze the supplied evidence.");
        AiAgentDefinition writer = new AiAgentDefinition(
                "writer", "Writer", "controlled update", "Apply the assigned update.");
        AiWorkflowPlan.Task analysis = new AiWorkflowPlan.Task(
                "Analyze", analyst.id(), "Analyze the supplied evidence.", null,
                "Analyzing", "Analyzed", AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan.Task update = new AiWorkflowPlan.Task(
                "Update", writer.id(), "Update record 42.", null,
                "Updating", "Updated", AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowNode analysisLeaf = new AiWorkflowNode(
                "analysis", "direct", true, null, "Analyzing", "Analyzed",
                null, "Summarizing", "Summarized", analysis, null, List.of(), Map.of());
        AiWorkflowNode updateLeaf = new AiWorkflowNode(
                "update", "direct", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", update, null, List.of(), Map.of());
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "root", "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", null, null,
                List.of(analysisLeaf, updateLeaf), Map.of());
        when(catalog.requireWorker(analyst.id())).thenReturn(analyst);
        when(catalog.requireWorker(writer.id())).thenReturn(writer);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(analysis, update), rootNode));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder analysisRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder updateRecorder = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(analyst.id()), eq(analysis.instruction()), any()))
                .thenReturn(analysisRecorder);
        when(root.forkSubagent(eq(writer.id()), eq(update.instruction()), any()))
                .thenReturn(updateRecorder);
        when(analysisRecorder.conversationId()).thenReturn("analysis-child");
        when(updateRecorder.conversationId()).thenReturn("update-child");
        when(updateRecorder.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 0) return new AiChatExecutor.Result("final answer");
            return new AiChatExecutor.Result(candidate.agentId() + " completed");
        });

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(root, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("final answer");
        ArgumentCaptor<AiChatExecutor.Context> calls =
                ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(3)).execute(calls.capture());
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> analyst.id().equals(candidate.agentId())).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.toolsEnabled()).isFalse();
                    assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
                    assertThat(candidate.history()).allSatisfy(message ->
                            assertThat(message.getText()).doesNotContain("INTERNAL_WORKFLOW_RECOVERY"));
                });
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> writer.id().equals(candidate.agentId())).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.toolsEnabled()).isTrue();
                    assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.FULL);
                });
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 0).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.toolPolicy())
                            .isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY);
                    assertThat(candidate.history().getLast().getText())
                            .contains("already executed", "never repeat them");
                });
        verify(analysisRecorder, never()).successfulDomainToolCallCount();
    }

    @Test
    void routesOneRealApprovalBatchThroughComposedWriteWorkersAndBackToEachWorker()
            throws Exception {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiMutationConfirmationService confirmations = mock(AiMutationConfirmationService.class);
        RepositoryFactory repositories = mock(RepositoryFactory.class);
        AiChatConversationRepository conversations = mock(AiChatConversationRepository.class);
        when(repositories.aiChatConversationRepository(any(), any())).thenReturn(conversations);
        when(confirmations.decideBatch(any(), any())).thenAnswer(invocation -> {
            List<AiMutationConfirmationService.BatchDecision> items = invocation.getArgument(1);
            return items.stream().map(item -> new AiMutationDecision(
                    new AiMutationConfirmationDecisionResponse(
                            item.confirmationRequestId(), null, "APPROVED", "APPROVE",
                            Instant.now().plusSeconds(60), null, null, null, null,
                            "grant-" + item.confirmationRequestId()), HttpStatus.OK)).toList();
        });
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setRequestTimeout(Duration.ofSeconds(2));
        AiMutationApprovalCoordinator approvals = new AiMutationApprovalCoordinator(
                confirmations, repositories, properties, null);
        AiAgentDefinition writerA = catalog.requireWorker("general-purpose");
        AiAgentDefinition writerB = writerA;
        AiWorkflowPlan.Task taskA = new AiWorkflowPlan.Task(
                "Update A", writerA.id(), "Update record A.", null, "Updating", "Updated",
                AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowPlan.Task taskB = new AiWorkflowPlan.Task(
                "Update B", writerB.id(), "Update record B.", null, "Updating", "Updated",
                AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowNode leafA = new AiWorkflowNode(
                "leaf-a", "direct", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", taskA, null, List.of(), Map.of());
        AiWorkflowNode leafB = new AiWorkflowNode(
                "leaf-b", "direct", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", taskB, null, List.of(), Map.of());
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "root", "orchestrator_workers", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", null, null,
                List.of(leafA, leafB), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "orchestrator_workers", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", List.of(taskA, taskB), rootNode));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childA = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childB = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(writerA.id()), eq(taskA.instruction()), any())).thenReturn(childA);
        when(root.forkSubagent(eq(writerB.id()), eq(taskB.instruction()), any())).thenReturn(childB);
        when(childA.conversationId()).thenReturn("child-a");
        when(childB.conversationId()).thenReturn("child-b");
        when(childA.successfulDomainToolCallCount()).thenReturn(0L);
        when(childB.successfulDomainToolCallCount()).thenReturn(0L);
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 0) {
                return new AiChatExecutor.Result("Both worker decisions were applied.");
            }
            String suffix = candidate.request().conversationId().endsWith("a") ? "a" : "b";
            AiPendingMutationApproval pending = new AiPendingMutationApproval(
                    new AiMutationConfirmationNotice(
                            "approval-" + suffix, "REQUESTED", Instant.now().plusSeconds(60),
                            "update_" + suffix, "{\"id\":1}"),
                    "update_" + suffix, "{\"id\":1}");
            candidate.approvalWaitLifecycle().suspendForApproval();
            Map<String, AiMutationApprovalResolution> decisions;
            try {
                decisions = approvals.awaitDecisions(
                        candidate.requester(), candidate.request().requestId(),
                        candidate.request().conversationId(), candidate.approvalScope(),
                        List.of(pending), notices::add);
            } finally {
                candidate.approvalWaitLifecycle().resumeAfterApproval();
            }
            assertThat(decisions.get("approval-" + suffix).approved()).isTrue();
            return new AiChatExecutor.Result("Worker " + suffix + " resumed.", Map.of(
                    "approvalBarrierResolved", true,
                    "approvalBarrierCount", 1,
                    "approvedMutationCount", 1,
                    "deniedMutationCount", 0));
        });
        ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE),
                "test", "Test User", null, false, List.of());
        ChatRequest request = new ChatRequest("Update both", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "ask",
                AiMultiAgentOptions.single(), null, null);
        AiChatExecutor.Context context = new AiChatExecutor.Context(
                request, List.of(), new UserMessage(request.prompt()), user,
                root, true, true, AiChatExecutor.ToolPolicy.FULL, 0);

        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, null, catalog, null, null, approvals,
                1, 1, Duration.ofSeconds(2), List.of())) {
            CompletableFuture<AiChatExecutor.Result> execution =
                    CompletableFuture.supplyAsync(() -> coordinator.execute(context));
            AiMutationApprovalBatchNotice batch = notices.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(batch).isNotNull();
            assertThat(batch.parallel()).isTrue();
            assertThat(batch.items()).extracting(
                    AiMutationApprovalBatchNotice.Item::confirmationRequestId)
                    .containsExactlyInAnyOrder("approval-a", "approval-b");
            approvals.decide(user, new AiMutationApprovalDecisionRequest(
                    batch.requestId(), batch.rootConversationId(), batch.batchId(),
                    batch.items().stream().map(item ->
                            new AiMutationApprovalDecisionRequest.ItemDecision(
                                    item.confirmationRequestId(), "APPROVE")).toList()));
            assertThat(execution.get(2, java.util.concurrent.TimeUnit.SECONDS).answer())
                    .isEqualTo("Both worker decisions were applied.");
        }
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

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThatThrownBy(() -> coordinator.execute(
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
                "Research the assigned record precisely.");
        when(catalog.requireWorker("evidence-researcher")).thenReturn(researcher);
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
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            result = coordinator.execute(context(root,
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
    void propagatesOneParallelApprovalBarrierToMutationCapableChildAgents() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiMutationApprovalCoordinator approvals = mock(AiMutationApprovalCoordinator.class);
        AiAgentDefinition firstAgent = new AiAgentDefinition(
                "writer-a", "Writer A", "writes A", "Update A precisely.");
        AiAgentDefinition secondAgent = new AiAgentDefinition(
                "writer-b", "Writer B", "writes B", "Update B precisely.");
        when(catalog.requireWorker(firstAgent.id())).thenReturn(firstAgent);
        when(catalog.requireWorker(secondAgent.id())).thenReturn(secondAgent);
        AiWorkflowPlan.Task first = new AiWorkflowPlan.Task(
                "Update A", firstAgent.id(), "Update record A.", null, "Updating", "Updated",
                AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowPlan.Task second = new AiWorkflowPlan.Task(
                "Update B", secondAgent.id(), "Update record B.", null, "Updating", "Updated",
                AiWorkflowPlan.ToolAccess.FULL);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", List.of(first, second)));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childTwo = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkParallelExecution(eq(firstAgent.id()), eq(first.instruction()), any()))
                .thenReturn(childOne);
        when(root.forkParallelExecution(eq(secondAgent.id()), eq(second.instruction()), any()))
                .thenReturn(childTwo);
        when(childOne.conversationId()).thenReturn("child-1");
        when(childTwo.conversationId()).thenReturn("child-2");
        when(childOne.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(childTwo.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        AiMutationApprovalScope firstScope = new AiMutationApprovalScope(
                "conversation-1", "group-1", "child-1", firstAgent.id(), first.label());
        AiMutationApprovalScope secondScope = new AiMutationApprovalScope(
                "conversation-1", "group-1", "child-2", secondAgent.id(), second.label());
        when(approvals.openParallelGroup(eq("conversation-1"), eq("request-1"), any()))
                .thenReturn(Map.of("child-1", firstScope, "child-2", secondScope));
        when(approvals.decisionTimeout()).thenReturn(Duration.ofSeconds(2));
        CountDownLatch approvalBarrier = new CountDownLatch(2);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            if (candidate.agentDepth() == 1) {
                candidate.approvalWaitLifecycle().suspendForApproval();
                approvalBarrier.countDown();
                if (!approvalBarrier.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Parallel workers did not reach approval together.");
                }
                candidate.approvalWaitLifecycle().resumeAfterApproval();
            }
            return new AiChatExecutor.Result(candidate.agentDepth() == 1
                    ? "mutation completed" : "summary");
        });

        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, null, catalog, null, null, approvals,
                1, 1, Duration.ofSeconds(2), List.of())) {
            assertThat(coordinator.execute(context(root, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("summary");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls =
                ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(3)).execute(calls.capture());
        List<AiChatExecutor.Context> children = calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList();
        assertThat(children).hasSize(2).allSatisfy(child ->
                assertThat(child.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.FULL));
        assertThat(children).extracting(AiChatExecutor.Context::approvalScope)
                .containsExactlyInAnyOrder(firstScope, secondScope);
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 0).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.toolPolicy())
                            .isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY);
                    assertThat(candidate.history().getLast().getText())
                            .contains("already executed", "never repeat them");
                });
        verify(approvals).participantFinished(firstScope);
        verify(approvals).participantFinished(secondScope);
    }

    @Test
    void routingUsesAnIndividualApprovalScopeInsteadOfOpeningAParallelBatch() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiMutationApprovalCoordinator approvals = mock(AiMutationApprovalCoordinator.class);
        when(approvals.decisionTimeout()).thenReturn(Duration.ofSeconds(2));
        AiAgentDefinition writer = new AiAgentDefinition(
                "writer", "Writer", "writes one selected record", "Apply the selected update.");
        when(catalog.requireWorker(writer.id())).thenReturn(writer);
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Update selected record", writer.id(), "Update record A.", null,
                "Updating", "Updated", AiWorkflowPlan.ToolAccess.FULL);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "routing", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", List.of(task)));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder lead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(lead);
        when(root.forkSubagent(eq(writer.id()), eq(task.instruction()), any())).thenReturn(child);
        when(child.conversationId()).thenReturn("routing-child");
        when(child.successfulDomainToolCallCount()).thenReturn(0L, 1L, 1L);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 1
                    ? "mutation completed" : "summary");
        });

        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, null, catalog, null, null, approvals,
                4, 4, Duration.ofSeconds(2), List.of())) {
            assertThat(coordinator.execute(context(root, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("summary");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls =
                ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(2)).execute(calls.capture());
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList())
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.approvalScope().parallel()).isFalse();
                    assertThat(candidate.approvalScope().participantId())
                            .isEqualTo("routing-child");
                });
        verify(approvals, never()).openParallelGroup(any(), any(), any());
    }

    @Test
    void harvestsAParallelWorkerThatCompletedBeforeTheDeadlineWasExhausted() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Evidence researcher", "current-data research",
                "Research the assigned record precisely.");
        when(catalog.requireWorker(researcher.id())).thenReturn(researcher);
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
        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, catalog, null, null, 16, 8, Duration.ofMillis(300))) {
            result = coordinator.execute(context(root, AiMultiAgentOptions.single(), 0));
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
                "Research the assignment.");
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
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

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(context(root,
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
    void chainDoesNotRestoreMutationAuthorityAfterAFullWorker() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition writer = new AiAgentDefinition(
                "general-purpose", "General purpose", "controlled update",
                "Apply the assigned update.");
        AiWorkflowPlan.Task update = new AiWorkflowPlan.Task(
                "Update", writer.id(), "Update record 42.", null,
                "Updating", "Updated", AiWorkflowPlan.ToolAccess.FULL);
        AiWorkflowNode worker = new AiWorkflowNode(
                "update", "direct", true, null, "Updating", "Updated",
                null, "Summarizing", "Summarized", update, null, List.of(), Map.of());
        AiWorkflowNode lead = new AiWorkflowNode(
                "answer", "direct", true, null, "Verifying", "Verified",
                null, "Answering", "Answered", null, null, List.of(), Map.of());
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "root", "chain", true, null, "Working", "Completed",
                null, "Answering", "Answered", null, null,
                List.of(worker, lead), Map.of());
        when(catalog.requireWorker(writer.id())).thenReturn(writer);
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "chain", true, null, "Working", "Completed",
                null, "Answering", "Answered", List.of(update), rootNode));

        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder workflowLead = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(workflowLead);
        when(root.forkSubagent(eq(writer.id()), eq(update.instruction()), any()))
                .thenReturn(child);
        when(child.conversationId()).thenReturn("mutation-child");
        when(child.successfulDomainToolCallCount()).thenReturn(0L, 1L);
        when(root.successfulDomainToolCallCount()).thenReturn(0L, 1L);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 1
                    ? "Record 42 was updated." : "The update is verified.");
        });

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(
                    context(root, AiMultiAgentOptions.single(), 0)).answer())
                    .isEqualTo("The update is verified.");
        }

        ArgumentCaptor<AiChatExecutor.Context> calls =
                ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(2)).execute(calls.capture());
        assertThat(calls.getAllValues().get(0).toolPolicy())
                .isEqualTo(AiChatExecutor.ToolPolicy.FULL);
        assertThat(calls.getAllValues().get(1)).satisfies(candidate -> {
            assertThat(candidate.agentDepth()).isZero();
            assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.READ_ONLY);
            assertThat(candidate.history().getLast()).isInstanceOfSatisfying(
                    UserMessage.class, reference -> assertThat(reference.getText())
                            .contains("INTERNAL_WORKFLOW_UPSTREAM", "Record 42 was updated."));
        });
    }

    @Test
    void chainRunsWorkersInOrderAndCarriesPriorEvidence() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition("general-purpose", "General purpose", "general",
                "Complete the assignment.");
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
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

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, catalog)) {
            assertThat(coordinator.execute(context(root,
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
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, mock(AiAgentCatalog.class))) {
            assertThatThrownBy(() -> coordinator.execute(context(mock(AiTrajectoryRecorder.class),
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
        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, evaluator, catalog, null, null,
                16, 8, Duration.ofSeconds(2))) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
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
    @SuppressWarnings("unchecked")
    void givesEveryComposedEvaluatorIterationASeparateExecutionIdentity() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiWorkflowEvaluator evaluator = mock(AiWorkflowEvaluator.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "researcher", "Researcher", "research", "Research the request.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Research", agent.id(), "Research now.", null, "Researching", "Researched");
        AiWorkflowNode rootNode = new AiWorkflowNode(
                "research", "direct", false, null, "Researching", "Researched",
                null, "Summarizing", "Summarized", task, null, List.of(), Map.of());
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "direct", false, null, "Researching", "Researched",
                null, "Summarizing", "Summarized", List.of(task), rootNode);
        when(planner.plan(any(), anyList())).thenReturn(plan, plan);
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("first research"))
                .thenReturn(new AiChatExecutor.Result("verified research"));
        when(evaluator.evaluate(any(), eq(plan), any(), eq(1), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.CONTINUE,
                        "Verify once more.", "Repeat the research."));
        when(evaluator.evaluate(any(), eq(plan), any(), eq(2), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.COMPLETE,
                        null, null));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder leadOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder leadTwo = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childTwo = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(leadOne, leadTwo);
        when(root.forkSubagent(eq(agent.id()), any(), any())).thenReturn(childOne, childTwo);
        when(childOne.conversationId()).thenReturn("research-child-1");
        when(childTwo.conversationId()).thenReturn("research-child-2");

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, evaluator, catalog, null, null,
                16, 8, Duration.ofSeconds(2))) {
            result = coordinator.execute(context(root, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("verified research");
        ArgumentCaptor<Map<String, Object>> leadNamespaces =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(root, times(2)).fork(leadNamespaces.capture());
        assertThat(leadNamespaces.getAllValues())
                .extracting(namespace -> namespace.get("fanout_id"))
                .containsExactly("request-1:composed:iteration-1",
                        "request-1:composed:iteration-2");
        verify(leadOne).terminalLifecycle(eq("multi_agent_completed"), any(), any());
        verify(leadTwo).terminalLifecycle(eq("multi_agent_completed"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void givesEveryDelegatedEvaluatorIterationASeparateExecutionIdentity() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiWorkflowEvaluator evaluator = mock(AiWorkflowEvaluator.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AiAgentDefinition agent = new AiAgentDefinition(
                "researcher", "Researcher", "research", "Research the request.");
        AiWorkflowPlan.Task task = new AiWorkflowPlan.Task(
                "Research", agent.id(), "Research now.", null,
                "Researching", "Researched", AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "orchestrator_workers", false, null, "Researching", "Researched",
                null, "Synthesizing", "Synthesized", List.of(task));
        when(planner.plan(any(), anyList())).thenReturn(plan, plan);
        when(catalog.requireWorker(agent.id())).thenReturn(agent);
        AtomicInteger synthesisCalls = new AtomicInteger();
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 1
                    ? "research evidence"
                    : "attempt " + synthesisCalls.incrementAndGet());
        });
        when(evaluator.evaluate(any(), eq(plan), any(), eq(1), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.CONTINUE,
                        "Verify once more.", "Repeat the research."));
        when(evaluator.evaluate(any(), eq(plan), any(), eq(2), eq(3)))
                .thenReturn(new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.COMPLETE,
                        null, null));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder leadOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder leadTwo = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childOne = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childTwo = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(leadOne, leadTwo);
        when(root.forkSubagent(eq(agent.id()), any(), any())).thenReturn(childOne, childTwo);
        when(childOne.conversationId()).thenReturn("research-child-1");
        when(childTwo.conversationId()).thenReturn("research-child-2");

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, evaluator, catalog, null, null,
                16, 8, Duration.ofSeconds(2))) {
            result = coordinator.execute(context(root, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("attempt 2");
        ArgumentCaptor<Map<String, Object>> leadNamespaces =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(root, times(2)).fork(leadNamespaces.capture());
        assertThat(leadNamespaces.getAllValues())
                .extracting(namespace -> namespace.get("fanout_id"))
                .allSatisfy(value -> assertThat(value.toString())
                        .startsWith("fanout-").contains("-iteration-"))
                .doesNotHaveDuplicates();
        assertThat(leadNamespaces.getAllValues())
                .extracting(namespace -> namespace.get("fanout_id").toString())
                .extracting(value -> value.substring(value.lastIndexOf("-iteration-")))
                .containsExactly("-iteration-1", "-iteration-2");
        verify(leadOne).terminalLifecycle(eq("multi_agent_completed"), any(), any());
        verify(leadTwo).terminalLifecycle(eq("multi_agent_completed"), any(), any());
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
        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, evaluator, mock(AiAgentCatalog.class), null, null,
                16, 8, Duration.ofSeconds(2))) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.traceMetadata())
                .containsEntry("workflow_iterations", 3)
                .containsEntry("evaluation_status", "iteration_limit")
                .containsEntry("remaining_objective", "Retrieve the evidence.");
        verify(planner, times(3)).plan(any(), anyList());
        // Evaluator feedback is internal input to the next Planner. It must not
        // create an English user-facing guide for a request in another language.
        verify(recorder, never()).guide(any(), any());
        verify(recorder, times(2)).resetGuideDeduplication();
    }

    @Test
    @SuppressWarnings("unchecked")
    void isolatesConcurrentDirectBranchesAsDurablePlannedSpecialists() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder rootRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder leadRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder firstRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder secondRecorder = mock(AiTrajectoryRecorder.class);
        when(rootRecorder.fork(any())).thenReturn(leadRecorder);
        when(rootRecorder.forkSubagent(any(), any(), any()))
                .thenReturn(firstRecorder, secondRecorder);
        when(firstRecorder.conversationId()).thenReturn("direct-branch-1");
        when(secondRecorder.conversationId()).thenReturn("direct-branch-2");
        when(firstRecorder.successfulDomainToolCallCount()).thenReturn(0L, 1L);
        when(secondRecorder.successfulDomainToolCallCount()).thenReturn(0L, 1L);
        AiWorkflowNode root = new AiWorkflowNode("root", "parallel", true, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(directLeaf("first"), directLeaf("second")), Map.of());
        AiWorkflowPlan plan = new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root);
        when(planner.plan(any())).thenReturn(plan);
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiChatExecutor.Context candidate = invocation.getArgument(0);
            return new AiChatExecutor.Result(candidate.agentDepth() == 0
                    ? "combined answer" : "branch evidence");
        });

        AiChatExecutor.Result result;
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(
                executor, planner, mock(AiAgentCatalog.class))) {
            result = coordinator.execute(
                    context(rootRecorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("combined answer");
        ArgumentCaptor<Map<String, Object>> branchNamespaces =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(rootRecorder, times(2)).forkSubagent(any(), any(), branchNamespaces.capture());
        assertThat(branchNamespaces.getAllValues()).allSatisfy(namespace -> assertThat(namespace)
                .containsEntry("execution_scope", "worker")
                .containsEntry("execution_kind", "multi_agent")
                .containsEntry("parent_node_id", "request-1:composed:lead"));
        assertThat(branchNamespaces.getAllValues())
                .extracting(namespace -> namespace.get("node_id"))
                .containsExactly("request-1:composed:worker:first",
                        "request-1:composed:worker:second");
        ArgumentCaptor<Map<String, Object>> leadMetadata =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(leadRecorder).lifecycle(eq("multi_agent_started"), any(),
                leadMetadata.capture());
        assertThat(leadMetadata.getValue()).containsEntry("agent_count", 2);
        verify(firstRecorder).lifecycle(eq("subagent_planned"), any(), any());
        verify(firstRecorder).lifecycle(eq("subagent_started"), any(), any());
        verify(firstRecorder).terminalLifecycle(eq("subagent_completed"), any(), any());
        verify(secondRecorder).lifecycle(eq("subagent_planned"), any(), any());
        verify(secondRecorder).lifecycle(eq("subagent_started"), any(), any());
        verify(secondRecorder).terminalLifecycle(eq("subagent_completed"), any(), any());
        ArgumentCaptor<AiChatExecutor.Context> branchCalls =
                ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor, times(3)).execute(branchCalls.capture());
        assertThat(branchCalls.getAllValues().stream()
                .filter(candidate -> candidate.agentDepth() == 1).toList())
                .extracting(candidate -> candidate.request().conversationId())
                .containsExactlyInAnyOrder("direct-branch-1", "direct-branch-2");
    }

    @Test
    void rejectsDuplicateComposedNodeIdsBeforeOpeningAnyRecorder() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AiWorkflowNode root = new AiWorkflowNode("root", "parallel", false, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(directLeaf("duplicate"), directLeaf("duplicate")), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(
                executor, planner, mock(AiAgentCatalog.class))) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(recorder, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("node ids must be unique", "duplicate");
        }

        verify(recorder, never()).fork(any());
        verify(recorder, never()).forkSubagent(any(), any(), any());
        verify(executor, never()).execute(any());
    }

    @Test
    void forcesReadOnlyAdmittedSynthesisForAContainerNestedInAParallelBranch() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.fork(any())).thenAnswer(ignored -> groundedBranchRecorder());
        when(recorder.forkSubagent(any(), any(), any()))
                .thenAnswer(ignored -> groundedBranchRecorder());
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
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, mock(AiAgentCatalog.class))) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0)
                    .withAgentIdentity("external-root-agent",
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE));
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
                .satisfies(candidate -> {
                    assertThat(candidate.toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.FULL);
                    assertThat(candidate.agentId()).isEqualTo("external-root-agent");
                    assertThat(candidate.executionPurpose()).isEqualTo(
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
                });
        assertThat(calls.getAllValues().stream()
                .filter(candidate -> "workflow-synthesizer".equals(candidate.agentId())).toList())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.executionPurpose()).isEqualTo(
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.SYNTHESIS);
                    assertThat(candidate.history().getFirst())
                            .isInstanceOfSatisfying(SystemMessage.class,
                                    system -> assertThat(system.getText())
                                            .contains("Synthesize the supplied workflow outputs",
                                                    "untrusted evidence"));
                });
    }

    @Test
    void retriesToolRequiredConcurrentDirectLeavesUntilTheyReadDomainData() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = mock(AiWorkflowPlanner.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.fork(any())).thenAnswer(ignored -> recoveredBranchRecorder());
        when(recorder.forkSubagent(any(), any(), any()))
                .thenAnswer(ignored -> recoveredBranchRecorder());
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
        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, mock(AiAgentCatalog.class))) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
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
        when(recorder.forkSubagent(any(), any(), any()))
                .thenAnswer(ignored -> branchRecorderWithoutDomainEvidence());
        AiWorkflowNode root = new AiWorkflowNode("root", "parallel", true, null,
                "Working", "Completed", null, "Synthesizing", "Synthesized",
                null, null, List.of(directLeaf("first"), directLeaf("second")), Map.of());
        when(planner.plan(any())).thenReturn(new AiWorkflowPlan(
                "parallel", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(), root));
        when(executor.execute(any()))
                .thenReturn(new AiChatExecutor.Result("No current evidence."));

        try (AiWorkflowExecutionCoordinator coordinator = coordinator(executor, planner, mock(AiAgentCatalog.class))) {
            assertThatThrownBy(() -> coordinator.execute(
                    context(recorder, AiMultiAgentOptions.single(), 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("All parallel workflow branches failed.");
        }

        verify(executor, times(4)).execute(any());
    }

    private AiWorkflowPlan parallelComposedPlan(
            AiWorkflowPlan.Task first, AiWorkflowPlan.Task second) {
        AiWorkflowNode firstNode = new AiWorkflowNode(
                "first", "direct", false, first.guideMessage(),
                first.activeVerb(), first.completedVerb(), null,
                "Synthesizing", "Synthesized", first, null, List.of(), Map.of());
        AiWorkflowNode secondNode = new AiWorkflowNode(
                "second", "direct", false, second.guideMessage(),
                second.activeVerb(), second.completedVerb(), null,
                "Synthesizing", "Synthesized", second, null, List.of(), Map.of());
        AiWorkflowNode root = new AiWorkflowNode(
                "root", "parallel", false, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", null, null,
                List.of(firstNode, secondNode), Map.of());
        return new AiWorkflowPlan(
                "parallel", false, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", List.of(first, second), root);
    }

    private AiWorkflowNode directLeaf(String id) {
        return new AiWorkflowNode(id, "direct", true, null, "Working", "Completed",
                null, "Synthesizing", "Synthesized", null, null, List.of(), Map.of());
    }

    private AiTrajectoryRecorder groundedBranchRecorder() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.conversationId()).thenReturn("branch-conversation");
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L, 1L);
        return recorder;
    }

    private AiTrajectoryRecorder recoveredBranchRecorder() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.conversationId()).thenReturn("branch-conversation");
        when(recorder.successfulDomainToolCallCount()).thenReturn(0L, 0L, 1L);
        return recorder;
    }

    private AiTrajectoryRecorder branchRecorderWithoutDomainEvidence() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.conversationId()).thenReturn("branch-conversation");
        return recorder;
    }

    private AiChatExecutor.Result deniedApprovalResult() {
        return new AiChatExecutor.Result(
                "The requested update was denied and was not executed.", Map.of(
                "approvalBarrierResolved", true,
                "approvalBarrierCount", 1,
                "approvedMutationCount", 0,
                "deniedMutationCount", 1));
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
        try (AiWorkflowExecutionCoordinator coordinator = new AiWorkflowExecutionCoordinator(
                executor, planner, null, mock(AiAgentCatalog.class), null, null,
                16, 8, Duration.ofSeconds(2), List.of(extension))) {
            result = coordinator.execute(context(recorder, AiMultiAgentOptions.single(), 0));
        }

        assertThat(result.answer()).isEqualTo("extension answer");
        assertThat(result.traceMetadata()).containsEntry("workflow", "custom_direct");
        verify(executor, never()).execute(any());
    }

    private AiWorkflowExecutionCoordinator coordinator(AiChatExecutor executor, AiWorkflowPlanner planner,
                                        AiAgentCatalog catalog) {
        AiAgentCatalog systemAgents = new AiAgentCatalog(new DefaultResourceLoader());
        return new AiWorkflowExecutionCoordinator(executor, planner, null, catalog, null, null,
                null, new WorkflowSynthesizerAgent(systemAgents),
                16, 8, Duration.ofSeconds(2), List.of());
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
