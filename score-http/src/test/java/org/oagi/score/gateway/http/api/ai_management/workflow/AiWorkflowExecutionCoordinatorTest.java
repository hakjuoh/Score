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
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
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
        AiTrajectoryRecorder analysisRecorder = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder updateRecorder = mock(AiTrajectoryRecorder.class);
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
        AiTrajectoryRecorder childA = mock(AiTrajectoryRecorder.class);
        AiTrajectoryRecorder childB = mock(AiTrajectoryRecorder.class);
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
        AiTrajectoryRecorder child = mock(AiTrajectoryRecorder.class);
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
