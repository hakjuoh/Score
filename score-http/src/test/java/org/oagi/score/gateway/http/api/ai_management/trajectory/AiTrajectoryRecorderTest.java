package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputTestFactory;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolFailureMessage;
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
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    void terminalSealAndSideEffectStartHaveOneAtomicOrdering() throws Exception {
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(AiChatConversationRepository.class), new ObjectMapper(), null,
                "conversation", "request", ignored -> { });
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch sealStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var action = pool.submit(() -> recorder.callWhileActive(() -> {
                actionStarted.countDown();
                try {
                    if (!releaseAction.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test action was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("test action interrupted");
                }
                return "completed";
            }));
            assertThat(actionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var seal = pool.submit(() -> {
                sealStarted.countDown();
                recorder.sealAgainstLateCallbacks();
            });
            assertThat(sealStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> seal.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseAction.countDown();
            assertThat(action.get(2, TimeUnit.SECONDS)).isEqualTo("completed");
            seal.get(2, TimeUnit.SECONDS);
            assertThatThrownBy(() -> recorder.callWhileActive(() -> "too late"))
                    .isInstanceOf(CancellationException.class);
        } finally {
            releaseAction.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void publishesAnElicitationWhileTheToolCallThatAsksForItIsStillRunning() throws Exception {
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(AiChatConversationRepository.class), new ObjectMapper(), mock(ScoreUser.class),
                "conversation-1", "request-1", events::add);
        CountDownLatch published = new CountDownLatch(1);
        // An MCP server elicits from inside the call it was invoked with: the notice must
        // reach the requester on another thread before the tool call can answer.
        ToolCallback elicitingTool = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("delete_context_category")
                        .description("Deletes one context category")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String input) {
                return call(input, new ToolContext(Map.of()));
            }

            @Override
            public String call(String input, ToolContext context) {
                Thread transport = new Thread(() -> {
                    recorder.elicitationRequired(new AiElicitationNotice("elicitation-1",
                            "request-1", "conversation-1", "Are you sure?", Map.of(),
                            Instant.now().plusSeconds(120)));
                    published.countDown();
                });
                transport.start();
                try {
                    if (!published.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the elicitation was never published");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("interrupted while eliciting");
                }
                return "{\"deleted\":true}";
            }
        };

        String output = recorder.recordingTools(() -> new ToolCallback[]{elicitingTool})
                .getToolCallbacks()[0].call("{\"ctx_category_id\":98}", new ToolContext(Map.of()));

        assertThat(output).contains("\"deleted\":true");
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("started", "elicitation_required", "completed");
        assertThat(events.get(1).metadata()).containsEntry("elicitationId", "elicitation-1");
    }

    @Test
    void publishesContentFreeLifecycleObservationsIndependentlyOfRealtimeDelivery() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<ExecutionObservation> observations = new ArrayList<>();
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        String sensitiveContent = "DO_NOT_SEND_THIS_TRAJECTORY_CONTENT_TO_OBSERVERS";
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", "model", "high",
                ignored -> { throw new IllegalStateException("realtime delivery failed"); },
                null, 0L, Map.of(), scope, observations::add,
                ExecutionObservationContext.noop());

        recorder.lifecycle("parallel_workflow_started", sensitiveContent,
                Map.of("fanout_id", "fanout-1", "workflow", "parallel",
                        "toolDetail", sensitiveContent));

        assertThat(observations).hasSize(1);
        AiExecutionLifecycle lifecycle = AiExecutionLifecycle.from(observations.getFirst())
                .orElseThrow();
        assertThat(lifecycle.subtype()).isEqualTo("parallel_workflow_started");
        assertThat(lifecycle.metadata()).containsEntry("fanout_id", "fanout-1");
        assertThat(lifecycle.metadata()).doesNotContainKey("toolDetail");
        assertThat(lifecycle.toString()).doesNotContain(sensitiveContent);
        verify(repository).append(eq("conversation-1"), any());
    }

    @Test
    void persistsBeforeObservationAndIsolatesAnObserverFailureFromRealtimeDelivery() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<String> order = new ArrayList<>();
        org.mockito.Mockito.doAnswer(ignored -> {
            order.add("persisted");
            return null;
        }).when(repository).append(eq("conversation-1"), any());
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", "model", "high",
                ignored -> order.add("realtime"), null, 0L, Map.of(), scope,
                ignored -> {
                    order.add("observed");
                    throw new IllegalStateException("optional listener unavailable");
                }, ExecutionObservationContext.noop());

        recorder.lifecycle("parallel_workflow_started", "private content",
                Map.of("fanout_id", "fanout-1", "workflow", "parallel"));

        assertThat(order).containsExactly("persisted", "observed", "realtime");
    }

    @Test
    void persistsRootTraceCorrelationOnEveryDerivedTrajectoryStep() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", "model", "high",
                ignored -> { }, null, 0L, Map.of(
                "trace_id", "trace-1", "root_span_id", "span-1", "service_version", "3.6.0"));

        recorder.lifecycle("multi_agent_started", "Started.", Map.of("fanout_id", "fanout-1"));
        recorder.fork(Map.of("node_id", "worker-1"))
                .lifecycle("subagent_started", "Started.", Map.of());

        ArgumentCaptor<AiChatTrajectoryStep> steps = ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.extra())
                .containsEntry("trace_id", "trace-1")
                .containsEntry("root_span_id", "span-1")
                .containsEntry("service_version", "3.6.0"));
    }

    @Test
    void forkParallelExecutionCreatesADurableParallelConversationAndWritesItsOwnSteps() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.openChild("conversation-1", "request-1",
                AiChatConversationKind.PARALLEL, "evidence-researcher",
                "Inspect Sync Purchase Order"))
                .thenReturn("child-conversation-1");
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "model", "high",
                ignored -> {});

        AiTrajectoryRecorder child = root.forkParallelExecution(
                "evidence-researcher", "Inspect Sync Purchase Order", Map.of(
                        "fanout_id", "fanout-1", "node_id", "fanout-1-agent-01",
                        "parent_node_id", "fanout-1-lead", "depth", 1,
                        "workflow", "parallel"));
        child.lifecycle("parallel_task_started", "Reviewing Sync Purchase Order.",
                Map.of("status", "started"));

        assertThat(child.conversationId()).isEqualTo("child-conversation-1");
        assertThat(child.conversationKind()).isEqualTo(AiChatConversationKind.PARALLEL);
        ArgumentCaptor<AiChatTrajectoryStep> steps = ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(3)).append(eq("child-conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).extracting(AiChatTrajectoryStep::messageKind)
                .containsExactly("settings_change", "parallel_assignment", "agent_lifecycle");
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.extra())
                .containsEntry("workflow", "parallel")
                .containsEntry("conversation_kind", "PARALLEL")
                .containsEntry("execution_kind", "parallel")
                .containsEntry("child_conversation_id", "child-conversation-1"));
        assertThat(steps.getAllValues().getLast().extra())
                .containsEntry("fanout_id", "fanout-1")
                .containsEntry("node_id", "fanout-1-agent-01")
                .containsEntry("lifecycle_subtype", "parallel_task_started");
    }

    @Test
    void forkNamespacesLifecycleTrajectoryAndRealtimeEventsWithTheSameAgentIds() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "model", "high", events::add);
        Map<String, Object> namespace = Map.of(
                "fanout_id", "fanout-abc",
                "node_id", "fanout-abc-agent-01",
                "parent_node_id", "fanout-abc-lead",
                "agent_name", "requirements-analyst",
                "agent_role", "requirements analysis",
                "execution_scope", "worker",
                "ordinal", 1,
                "depth", 1);
        AiTrajectoryRecorder child = root.fork(namespace);

        child.lifecycle("subagent_started", "Specialist started.", Map.of("status", "started"));
        child.progress("Inspecting current data.");

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.extra())
                .containsEntry("fanout_id", "fanout-abc")
                .containsEntry("node_id", "fanout-abc-agent-01")
                .containsEntry("parent_node_id", "fanout-abc-lead")
                .containsEntry("ordinal", 1)
                .containsEntry("depth", 1));
        assertThat(steps.getAllValues().getFirst().messageKind()).isEqualTo("agent_lifecycle");
        assertThat(steps.getAllValues().getFirst().extra()).containsEntry("status", "started");
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("subagent_started", "progress");
        assertThat(events).allSatisfy(event -> assertThat(event.metadata())
                .containsEntry("fanoutId", "fanout-abc")
                .containsEntry("nodeId", "fanout-abc-agent-01")
                .containsEntry("agentId", "fanout-abc-agent-01")
                .containsEntry("agentName", "requirements-analyst")
                .containsEntry("agentRole", "requirements analysis")
                .containsEntry("executionScope", "worker"));
    }

    @Test
    void terminalLifecycleRejectsEveryLateProviderAndToolCallback() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_libraries").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("{\"items\":[]}");

        recorder.terminalLifecycle("subagent_failed", "Specialist timed out.",
                Map.of("status", "failed", "reason", "timeout"));
        recorder.progress("late progress");
        recorder.contentDelta("late content");
        assertThatThrownBy(() -> recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of())))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        verify(callback, org.mockito.Mockito.never()).call(anyString(), any(ToolContext.class));
        verify(repository, times(1)).append(eq("conversation-1"), any());
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("subagent_failed");
    }

    @Test
    void terminalLifecycleStillSealsWhenRealtimeDeliveryFails() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {
                    throw new IllegalStateException("broker unavailable");
                });

        recorder.terminalLifecycle(
                "subagent_failed", "Specialist failed.", Map.of("status", "failed"));
        recorder.progress("late progress");

        verify(repository, times(1)).append(eq("conversation-1"), any());
    }

    @Test
    void doesNotRecordSyntheticApprovalDecisionResponsesAsFreshToolExecutions() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});
        ToolResponseMessage response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("approved-1", "create_business_context",
                        "{\"biz_ctx_id\":18}"),
                new ToolResponseMessage.ToolResponse("approved-2", "delete_business_context",
                        "{\"error\":\"MUTATION_CONFIRMATION_DENIED\"}"))).build();

        recorder.recordToolResponses(List.of(response));

        verifyNoInteractions(repository);
        assertThat(recorder.completedToolCallCount()).isZero();
        assertThat(recorder.executedDomainToolCallCount()).isZero();
    }

    @Test
    void recordsAnExactDeniedRetryAsNonExecutedInsteadOfSuccessful() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), mock(ScoreUser.class),
                "conversation-1", "request-1", events::add);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("delete_business_context")
                .description("delete")
                .inputSchema("{\"type\":\"object\"}")
                .build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn(
                "{\"error\":\"MUTATION_CONFIRMATION_DENIED\","
                        + "\"message\":\"The user denied this data-changing tool call.\"}");

        String output = recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0].call("{\"id\":1}", new ToolContext(Map.of()));

        assertThat(output).contains(AiMutationToolGuard.MUTATION_CONFIRMATION_DENIED);
        assertThat(recorder.completedDomainToolCallCount()).isEqualTo(1);
        assertThat(recorder.successfulDomainToolCallCount()).isZero();
        assertThat(recorder.executedDomainToolCallCount()).isZero();
        assertThat(events.stream().filter(event -> "tool_call".equals(event.type()))
                .filter(event -> !"started".equals(event.subtype()))
                .map(AiExecutionEvent::subtype)).containsExactly("denied");
        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues().getLast().extra())
                .containsEntry("tool_status", "denied")
                .containsEntry("success", false);
    }

    @Test
    void excludesToolDiscoveryFromTheCompletedDomainToolCount() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});
        ToolResponseMessage responses = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("search-1", "toolSearchTool",
                        "[\"get_context_schemes\"]"),
                new ToolResponseMessage.ToolResponse("read-1", "get_context_schemes",
                        "{\"items\":[]}"))).build();

        recorder.recordToolResponses(List.of(responses));

        assertThat(recorder.completedToolCallCount()).isEqualTo(2);
        assertThat(recorder.completedDomainToolCallCount()).isEqualTo(1);
        assertThat(recorder.successfulDomainToolCallCount()).isEqualTo(1);
    }

    @Test
    void sharesRequestWideExecutionEvidenceAcrossForkedRecorders() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});
        AiTrajectoryRecorder worker = root.fork(Map.of("node_id", "request-1:worker"));
        ToolResponseMessage responses = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("read-1", "get_context_schemes",
                        "{\"items\":[]}"),
                new ToolResponseMessage.ToolResponse("blocked-1", "create_business_context",
                        "{\"error\":\"MUTATION_CONFIRMATION_REQUIRED\",\"confirmationRequestId\":\"c-1\"}"),
                new ToolResponseMessage.ToolResponse("stopped-1", "update_business_context",
                        "{\"error\":\"REQUEST_STOPPING\"}"))).build();

        worker.recordToolResponses(List.of(responses));

        // Evidence counters are request-wide: a fork's executions are visible at
        // the root, executed excludes intercepted calls, and a stop interception
        // is neither an execution nor a pending approval.
        assertThat(root.executedDomainToolCallCount()).isEqualTo(1);
        assertThat(root.pendingApprovalCount()).isEqualTo(1);
        worker.mutationApprovalsResolved(List.of(new AiPendingMutationApproval(
                new AiMutationConfirmationNotice("c-1", "REQUESTED",
                        Instant.now().plusSeconds(60), "create_business_context", "{}"),
                "create_business_context", "{}")));
        assertThat(root.pendingApprovalCount()).isZero();
        assertThat(worker.pendingApprovalCount()).isZero();
        // Answer-segment boundary counters stay local to the observing recorder.
        assertThat(root.completedToolCallCount()).isZero();
        assertThat(worker.completedToolCallCount()).isEqualTo(3);
        assertThat(worker.successfulDomainToolCallCount()).isEqualTo(1);
        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(6)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues().stream()
                .filter(step -> "tool_call".equals(step.messageKind()))
                .map(step -> step.extra().get("tool_status")))
                .containsExactly("completed", "blocked", "cancelled");
    }

    @Test
    void countsRepeatedExactBlockedCallsAsOnePendingConfirmation() {
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(AiChatConversationRepository.class), new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", ignored -> {});
        ToolResponseMessage responses = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("blocked-1", "update_business_context",
                        "{\"error\":\"MUTATION_CONFIRMATION_REQUIRED\","
                                + "\"confirmationRequestId\":\"confirmation-1\"}"),
                new ToolResponseMessage.ToolResponse("blocked-2", "update_business_context",
                        "{\"error\":\"MUTATION_CONFIRMATION_REQUIRED\","
                                + "\"confirmationRequestId\":\"confirmation-1\"}"))).build();

        recorder.recordToolResponses(List.of(responses));

        assertThat(recorder.pendingApprovalCount()).isEqualTo(1);
        recorder.mutationApprovalsResolved(List.of(new AiPendingMutationApproval(
                new AiMutationConfirmationNotice("confirmation-1", "REQUESTED",
                        Instant.now().plusSeconds(60), "update_business_context", "{\"id\":1}"),
                "update_business_context", "{\"id\":1}")));
        assertThat(recorder.pendingApprovalCount()).isZero();
    }

    @Test
    void recognizesApprovalSentinelsOnlyAsExactTopLevelJsonErrors() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);
        ToolResponseMessage responses = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("text-1", "create_business_context",
                        "{\"message\":\"example \\\"error\\\":\\\"MUTATION_CONFIRMATION_REQUIRED\\\"\"}"),
                new ToolResponseMessage.ToolResponse("nested-1", "create_business_context",
                        "{\"detail\":{\"error\":\"REQUEST_STOPPING\"}}"),
                new ToolResponseMessage.ToolResponse("exact-1", "create_business_context",
                        "{\"error\":\"MUTATION_CONFIRMATION_REQUIRED\"}"))).build();

        recorder.recordToolResponses(List.of(responses));

        assertThat(events.stream().filter(event -> "tool_call".equals(event.type()))
                .filter(event -> !"started".equals(event.subtype()))
                .map(AiExecutionEvent::subtype))
                .containsExactly("completed", "completed", "blocked");
    }

    @Test
    void persistsAndPublishesAWorkflowResultAsVisibleChatContent() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);

        recorder.workflowResult(AgentOutputTestFactory.publicOutput(
                "Three reconciled counts."), Map.of(
                "node_id", "main:1:release-count", "depth", 1));

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().messageKind()).isEqualTo("workflow_result");
        assertThat(step.getValue().visibility()).isEqualTo("visible");
        assertThat(step.getValue().message()).isEqualTo("Three reconciled counts.");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.subtype()).isEqualTo("workflow_result");
            assertThat(event.content()).isEqualTo("Three reconciled counts.");
            assertThat(event.metadata()).containsEntry("node_id", "main:1:release-count");
        });
    }

    @Test
    void rejectsAWorkflowResultWithoutPublicGuardrailEvidence() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);

        assertThatThrownBy(() -> recorder.workflowResult(
                new AgentOutput("Unreviewed result."), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PUBLIC");
        verifyNoInteractions(repository);
        assertThat(events).isEmpty();
    }

    @Test
    void publishesTheProviderErrorBeforeTheRetryNarration() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);

        recorder.providerRetry(2, 10, 15_000L, "Rate limited.",
                "org.springframework.ai.retry.TransientAiException", 429);

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).extracting(AiChatTrajectoryStep::messageKind)
                .containsExactly("provider_error", "provider_retry");
        assertThat(steps.getAllValues()).extracting(AiChatTrajectoryStep::message)
                .containsExactly("Rate limited.",
                        "The model provider request failed; retrying (attempt 2 of 10).");
        assertThat(steps.getAllValues()).extracting(AiChatTrajectoryStep::visibility)
                .containsExactly("debug", "debug");
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.extra())
                .containsEntry("attempt", 2)
                .containsEntry("max_attempts", 10)
                .containsEntry("delay_millis", 15_000L)
                .containsEntry("reason", "Rate limited.")
                .containsEntry("failure_class", "org.springframework.ai.retry.TransientAiException")
                .containsEntry("status_code", 429));
        assertThat(events).extracting(AiExecutionEvent::subtype)
                .containsExactly("provider_error", "provider_retry");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.metadata())
                    .containsEntry("attempt", 2)
                    .containsEntry("max_attempts", 10)
                    .containsEntry("delay_millis", 15_000L);
        });
    }

    @Test
    void keepsProviderResponseTextInAtifButOutOfLifecycleObservations() {
        String secret = "SECRET-provider-body-account@example.test";
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<ExecutionObservation> observations = new ArrayList<>();
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", "model", "high",
                ignored -> { }, null, 0L, Map.of(), scope, observations::add,
                ExecutionObservationContext.noop());

        recorder.providerRetry(1, 3, 250L, secret,
                "org.springframework.ai.retry.TransientAiException", 429);

        ArgumentCaptor<AiChatTrajectoryStep> persisted =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), persisted.capture());
        assertThat(persisted.getAllValues()).allSatisfy(step ->
                assertThat(step.extra()).containsEntry("reason", secret));
        assertThat(observations).allSatisfy(observation -> {
            AiExecutionLifecycle observed = AiExecutionLifecycle.from(observation).orElseThrow();
            assertThat(observed.metadata()).doesNotContainKey("reason");
            assertThat(observed.toString()).doesNotContain(secret);
        });
    }

    @Test
    void reemitsAConsecutiveGuideAfterTheDeduplicationWindowReopens() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);

        recorder.guide("Continuing with the remaining objective: verify.", Map.of());
        recorder.guide("Continuing with the remaining objective: verify.", Map.of());
        recorder.resetGuideDeduplication();
        recorder.guide("Continuing with the remaining objective: verify.", Map.of());

        assertThat(events.stream().filter(event -> "guide".equals(event.subtype()))).hasSize(2);
    }

    @Test
    void correlatesModelToolCallWithItsObservationAndUiDetail() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        recorder.useModelProvider("anthropic");

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
        assertThat(recorder.completedDomainToolCallCount()).isEqualTo(1);
        assertThat(recorder.successfulDomainToolCallCount()).isEqualTo(1);
        verify(repository).updateObservation(eq("conversation-1"), eq(42L), any());
        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(3)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues().get(0).toolCalls()).singleElement()
                .satisfies(tool -> assertThat(tool.get("tool_call_id")).isEqualTo("call-1"));
        assertThat(steps.getAllValues().get(0).metrics())
                .containsEntry("prompt_tokens", 107L)
                .containsEntry("cached_tokens", 100L);
        assertThat(steps.getAllValues().get(1).messageKind()).isEqualTo("tool_call_update");
        assertThat(steps.getAllValues().get(2).messageKind()).isEqualTo("tool_call");
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
    void addsAnthropicCacheTokensToTheProviderReportedInputCount() {
        Map<String, Object> metrics = recordedMetrics(
                "anthropic", new DefaultUsage(2, 4, 6, null, 100L, 5L));

        assertThat(metrics)
                .containsEntry("prompt_tokens", 107L)
                .containsEntry("cached_tokens", 100L)
                .containsEntry("context_input_tokens", 107L)
                .containsEntry("context_estimated", false);
    }

    @Test
    void keepsOpenAiCompatibleCachedTokensAsASubsetOfThePromptTotal() {
        for (String providerType : List.of("openai", "azure-openai")) {
            Map<String, Object> metrics = recordedMetrics(
                    providerType, new DefaultUsage(107, 4, 111, null, 100L, 0L));

            assertThat(metrics)
                    .as(providerType)
                    .containsEntry("prompt_tokens", 107L)
                    .containsEntry("cached_tokens", 100L)
                    .containsEntry("context_input_tokens", 107L)
                    .containsEntry("context_estimated", false);
        }
    }

    @Test
    void omitsPromptTokensWhenCacheAccountingSemanticsAreUnknown() {
        Map<String, Object> metrics = recordedMetrics(
                "custom-provider", new DefaultUsage(107, 4, 111, null, 100L, 0L));

        assertThat(metrics)
                .doesNotContainKey("prompt_tokens")
                .containsEntry("provider_reported_prompt_tokens", 107L)
                .containsEntry("prompt_tokens_complete", false)
                .containsEntry("prompt_token_accounting", "unknown")
                .containsEntry("context_input_tokens", 207L)
                .containsEntry("context_estimated", true);
    }

    @Test
    void keepsCompleteProviderPromptTokensWhenTheContextFloorIsHigher() {
        Map<String, Object> anthropic = recordedMetrics(
                "anthropic", new DefaultUsage(2, 4, 6, null, 100L, 5L), 5_000L);
        Map<String, Object> openAi = recordedMetrics(
                "openai", new DefaultUsage(107, 4, 111, null, 100L, 0L), 5_000L);

        assertThat(anthropic)
                .containsEntry("prompt_tokens", 107L)
                .containsEntry("prompt_tokens_complete", true)
                .containsEntry("context_input_tokens", 5_000L)
                .containsEntry("context_estimated", true)
                .doesNotContainKey("provider_reported_prompt_tokens");
        assertThat(openAi)
                .containsEntry("prompt_tokens", 107L)
                .containsEntry("prompt_tokens_complete", true)
                .containsEntry("context_input_tokens", 5_000L)
                .containsEntry("context_estimated", true)
                .doesNotContainKey("provider_reported_prompt_tokens");
    }

    @Test
    void omitsAnthropicStreamingPromptTokensWhenSpringLosesCacheUsage() {
        DefaultUsage incompleteStreamingUsage = new DefaultUsage(
                2, 4, 6, null, null, null);

        Map<String, Object> metrics = recordedMetrics(
                "anthropic", incompleteStreamingUsage, 0L, true);

        assertThat(metrics)
                .doesNotContainKey("prompt_tokens")
                .containsEntry("provider_reported_prompt_tokens", 2L)
                .containsEntry("prompt_tokens_complete", false)
                .containsEntry("prompt_token_accounting", "cache_excluded")
                .containsEntry("context_input_tokens", 2L)
                .containsEntry("context_estimated", true);
    }

    @Test
    void keepsSubagentPreToolNarrationOutOfTheVisibleGuideStream() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        AiTrajectoryRecorder child = root.fork(Map.of(
                "agent_id", "evidence-researcher", "depth", 1));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "toolSearchTool", "{\"query\":\"context scheme\"}");
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("I am read-only and cannot create records.")
                        .toolCalls(List.of(call))
                        .build())));

        child.recordModelResponse(response, "assistant");

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getValue().messageKind()).isEqualTo("model_call");
        assertThat(events).isEmpty();
    }

    @Test
    void keepsConcurrentDirectBranchNarrationOutOfTheVisibleGuideStream() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        AiTrajectoryRecorder branch = root.fork(Map.of(
                "node_id", "request-1:lookup", "concurrent_branch", true, "depth", 1));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "toolSearchTool", "{\"query\":\"context scheme\"}");
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("I am read-only and cannot create records.")
                        .toolCalls(List.of(call))
                        .build())));

        branch.recordModelResponse(response, "assistant");

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getValue().messageKind()).isEqualTo("model_call");
        assertThat(events).isEmpty();
    }

    @Test
    void keepsPreToolCandidateNarrationPrivateUntilOutputGuardrailsAcceptIt() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "get_context_schemes", "{}");
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("I’ll verify the current context schemes.")
                        .toolCalls(List.of(call))
                        .build())));

        root.recordModelResponse(response, "assistant");

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getValue().message()).isEmpty();
        assertThat(steps.getValue().extra())
                .containsEntry("candidate_content_suppressed", true);
        assertThat(events).isEmpty();
    }

    @Test
    void recordsTheGuardReadOnlyClassificationOnEveryToolStep() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});
        recorder.readOnlyToolNames(java.util.Set.of("get_business_context"));
        ToolCallback read = mock(ToolCallback.class);
        when(read.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_business_context").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(read.call(anyString(), any(ToolContext.class))).thenReturn("{}");
        ToolCallback mutation = mock(ToolCallback.class);
        when(mutation.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("create_business_context").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(mutation.call(anyString(), any(ToolContext.class))).thenReturn("{}");

        ToolCallbackProvider wrapped = recorder.recordingTools(() -> new ToolCallback[]{read, mutation});
        wrapped.getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));
        wrapped.getToolCallbacks()[1].call("{}", new ToolContext(Map.of()));

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(4)).append(eq("conversation-1"), steps.capture());
        List<AiChatTrajectoryStep> terminal = steps.getAllValues().stream()
                .filter(step -> "tool_call".equals(step.messageKind())).toList();
        assertThat(terminal.get(0).extra())
                .containsEntry("tool_name", "get_business_context")
                .containsEntry("read_only", true);
        assertThat(terminal.get(1).extra())
                .containsEntry("tool_name", "create_business_context")
                .containsEntry("read_only", false);
    }

    @Test
    void redactsSecretsFromPersistedToolArgumentsAndResults() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
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
        verify(repository, times(2)).append(eq("conversation-1"), step.capture());
        AiChatTrajectoryStep terminal = step.getAllValues().stream()
                .filter(candidate -> "tool_call".equals(candidate.messageKind())).findFirst().orElseThrow();
        assertThat(terminal.message()).contains("[REDACTED]")
                .contains("\"authorId\":42", "\"sessionCount\":3")
                .doesNotContain("super-secret", "exposed-token", "hunter2", "session-value",
                        "browser-cookie", "plain-token", "credential-value");
        assertThat(terminal.extra().toString()).contains("[REDACTED]")
                .doesNotContain("hunter2", "credential-value");
    }

    @Test
    void storesOnlyAGenericMessageWhenAToolFailureContainsInternalDetails() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
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
        assertThat(recorder.successfulDomainToolCallCount()).isZero();

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), step.capture());
        AiChatTrajectoryStep terminal = step.getAllValues().stream()
                .filter(candidate -> "tool_call".equals(candidate.messageKind())).findFirst().orElseThrow();
        assertThat(terminal.message())
                .contains("The tool could not complete the request.")
                .doesNotContain("Details were recorded in the server log")
                .doesNotContain("SQL syntax", "app_user", "exposed-token");
        assertThat(events.getLast().metadata().get("toolDetail").toString())
                .doesNotContain("SQL syntax", "app_user", "exposed-token");
    }

    @Test
    void surfacesTheSanitizedValidationErrorReturnedByTheMcpTool() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(mcpValidationFailure(definition));

        assertThatThrownBy(() -> recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0].call("{\"biz_ctx_list\":\"[83]\"}",
                        new ToolContext(Map.of())))
                .isInstanceOf(ToolExecutionException.class);

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), steps.capture());
        AiChatTrajectoryStep terminal = steps.getAllValues().getLast();
        assertThat(terminal.message())
                .contains("Error: biz_ctx_list must be a comma-separated list of integers.")
                .doesNotContain("TextContent", "Details were recorded in the server log");
        assertThat(events.getLast().metadata().get("toolDetail").toString())
                .contains("biz_ctx_list must be a comma-separated list of integers.");
    }

    @Test
    void rejectsMcpErrorsThatContainCredentialsInsteadOfTreatingAuthorshipAsSafety() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        ToolExecutionException failure = new ToolExecutionException(definition,
                new IllegalStateException(
                        "Error calling tool: [TextContent[annotations=null, "
                                + "text=Validation failed; Authorization=Bearer exposed-token, "
                                + "meta=null]]"));

        assertThat(AiToolFailureMessage.userMessage(failure))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE)
                .doesNotContain("exposed-token", "TextContent");
    }

    @Test
    void rejectsWrappedMcpInternalErrorsForgedWrappersAndOversizedDetails() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();

        assertThat(AiToolFailureMessage.userMessage(mcpFailure(definition,
                "biz_ctx_list must be valid; SQL SELECT token_hash FROM app_user")))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE);
        assertThat(AiToolFailureMessage.userMessage(mcpFailure(definition,
                "biz_ctx_list must be valid; apiKey 'sk-super-secret-value'")))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE);
        assertThat(AiToolFailureMessage.userMessage(new ToolExecutionException(definition,
                new IllegalStateException("forged prefix Error calling tool: [TextContent[annotations=null, "
                        + "text=biz_ctx_list must be an integer., meta=null]]"))))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE);
        assertThat(AiToolFailureMessage.userMessage(mcpFailure(definition,
                "biz_ctx_list must be " + "x".repeat(20_000))))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE);
    }

    @Test
    void rejectsCompoundCredentialFieldsAndAuthenticationLanguage() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();

        assertThat(List.of(
                "userPassword must be hunter2.",
                "authToken must be abcdefghijklmnop.",
                "user must be authenticated.",
                "user must be authorized."))
                .allSatisfy(detail -> assertThat(AiToolFailureMessage.userMessage(
                        mcpFailure(definition, detail)))
                        .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE));
    }

    @Test
    void rejectsOpaqueProviderCredentialsAndLongTokenLikeValues() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();

        assertThat(List.of(
                "value must be AKIAIOSFODNN7EXAMPLE.",
                "value must be ghp_0123456789abcdef0123456789abcdef.",
                "value must be " + "xoxb" + "-123456789012-abcdefghijklmnop.",
                "value must be opaqueCredential1234567890abcd."))
                .allSatisfy(detail -> assertThat(AiToolFailureMessage.userMessage(
                        mcpFailure(definition, detail)))
                        .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE));
    }

    @Test
    void rejectsMalformedOrMultipleMcpTextContents() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        ToolExecutionException multiple = new ToolExecutionException(definition,
                new IllegalStateException("Error calling tool: [TextContent[annotations=null, "
                        + "text=biz_ctx_list must be an integer., meta=null], "
                        + "TextContent[annotations=null, text=SQL password leaked, meta=null]]"));
        ToolExecutionException malformed = new ToolExecutionException(definition,
                new IllegalStateException("Error calling tool: [TextContent[text=secret"));

        assertThat(AiToolFailureMessage.userMessage(multiple))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE);
        assertThat(AiToolFailureMessage.userMessage(malformed))
                .isEqualTo(AiToolFailureMessage.GENERIC_MESSAGE);
    }

    @Test
    void narratesACorrectedArgumentRetryWhenTheModelRetriesSilently() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", events::add);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(mcpValidationFailure(definition))
                .thenReturn("{\"top_level_asbiep_id\":3}");
        ToolCallback recorded = recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0];

        assertThatThrownBy(() -> recorded.call(
                "{\"asccp_manifest_id\":722970,\"biz_ctx_list\":\"[83]\"}",
                new ToolContext(Map.of())))
                .isInstanceOf(ToolExecutionException.class);

        AssistantMessage.ToolCall retry = new AssistantMessage.ToolCall(
                "retry-call", "function", "create_top_level_asbiep",
                "{\"asccp_manifest_id\":722970,\"biz_ctx_list\":\"83\"}");
        recorder.recordModelResponse(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().toolCalls(List.of(retry)).build()))), "assistant");
        assertThat(recorded.call(
                "{\"asccp_manifest_id\":722970,\"biz_ctx_list\":\"83\"}",
                new ToolContext(Map.of())))
                .isEqualTo("{\"top_level_asbiep_id\":3}");

        assertThat(events.stream().filter(event -> "guide".equals(event.subtype())))
                .singleElement().satisfies(event -> {
                    assertThat(event.content()).isEqualTo(
                            "The previous create_top_level_asbiep call failed. "
                                    + "I corrected the tool arguments and am retrying it.");
                    assertThat(event.metadata())
                            .containsEntry("tool_retry", true)
                            .containsEntry("tool_name", "create_top_level_asbiep");
                });
        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(6)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).extracting(AiChatTrajectoryStep::messageKind)
                .containsExactly("tool_call_update", "tool_call", "model_call", "guide",
                        "tool_call_update", "tool_call");
    }

    @Test
    void emitsFallbackRetryNarrationWhenTheModelsGuideWasDeduplicated() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(mcpValidationFailure(definition))
                .thenReturn("{\"top_level_asbiep_id\":3}");
        ToolCallback recorded = recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0];
        String repeatedGuide = "I’ll correct the business context argument and retry.";
        assertThat(recorder.guide(repeatedGuide, Map.of())).isTrue();

        assertThatThrownBy(() -> recorded.call("{\"biz_ctx_list\":\"[83]\"}",
                new ToolContext(Map.of()))).isInstanceOf(ToolExecutionException.class);
        AssistantMessage.ToolCall retry = new AssistantMessage.ToolCall(
                "retry-call", "function", "create_top_level_asbiep",
                "{\"biz_ctx_list\":\"83\"}");
        recorder.recordModelResponse(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(repeatedGuide)
                        .toolCalls(List.of(retry)).build()))), "assistant");
        recorded.call("{\"biz_ctx_list\":\"83\"}", new ToolContext(Map.of()));

        assertThat(events.stream().filter(event -> "guide".equals(event.subtype()))
                .map(AiExecutionEvent::content)).containsExactly(
                repeatedGuide,
                "The previous create_top_level_asbiep call failed. "
                        + "I corrected the tool arguments and am retrying it.");
    }

    @Test
    void emitsTheMandatoryGuideBeforeEveryConsecutiveSilentRetry() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        java.util.concurrent.atomic.AtomicLong storedId =
                new java.util.concurrent.atomic.AtomicLong(40L);
        when(repository.append(eq("conversation-1"), any()))
                .thenAnswer(ignored -> new AiChatStoredStep(
                        storedId.incrementAndGet(), 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(mcpValidationFailure(definition))
                .thenThrow(mcpValidationFailure(definition))
                .thenReturn("{\"top_level_asbiep_id\":3}");
        ToolCallback recorded = recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0];

        assertThatThrownBy(() -> recorded.call("{\"biz_ctx_list\":\"[83]\"}",
                new ToolContext(Map.of()))).isInstanceOf(ToolExecutionException.class);
        recordSilentToolCall(recorder, "retry-1", "{\"biz_ctx_list\":\"83\"}");
        assertThatThrownBy(() -> recorded.call("{\"biz_ctx_list\":\"83\"}",
                new ToolContext(Map.of()))).isInstanceOf(ToolExecutionException.class);
        recordSilentToolCall(recorder, "retry-2", "{\"biz_ctx_list\":\"84\"}");
        recorded.call("{\"biz_ctx_list\":\"84\"}", new ToolContext(Map.of()));

        assertThat(events.stream().filter(event -> "guide".equals(event.subtype()))
                .map(AiExecutionEvent::content)).containsExactly(
                "The previous create_top_level_asbiep call failed. "
                        + "I corrected the tool arguments and am retrying it.",
                "The previous create_top_level_asbiep call failed. "
                        + "I corrected the tool arguments and am retrying it.");
    }

    @Test
    void emitsDeterministicSyntheticRetryNarration() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(mcpValidationFailure(definition))
                .thenReturn("{\"top_level_asbiep_id\":3}");
        ToolCallback recorded = recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0];

        assertThatThrownBy(() -> recorded.call("{\"biz_ctx_list\":\"[83]\"}",
                new ToolContext(Map.of()))).isInstanceOf(ToolExecutionException.class);
        recordSilentToolCall(recorder, "retry-call", "{\"biz_ctx_list\":\"83\"}");
        recorded.call("{\"biz_ctx_list\":\"83\"}", new ToolContext(Map.of()));

        assertThat(events.stream().filter(event -> "guide".equals(event.subtype())))
                .singleElement().extracting(AiExecutionEvent::content)
                .isEqualTo("The previous create_top_level_asbiep call failed. "
                        + "I corrected the tool arguments and am retrying it.");
    }

    @Test
    void keepsWorkerRetryNarrationOutOfTheLeadConversation() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(42L, 3L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", events::add);
        AiTrajectoryRecorder worker = root.fork(Map.of(
                "node_id", "request-1:worker", "agent_id", "request-1:worker"));
        ToolDefinition definition = ToolDefinition.builder()
                .name("create_top_level_asbiep").description("test")
                .inputSchema("{\"type\":\"object\"}").build();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(mcpValidationFailure(definition))
                .thenReturn("{\"top_level_asbiep_id\":3}");
        ToolCallback recorded = worker.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0];

        assertThatThrownBy(() -> recorded.call("{\"biz_ctx_list\":\"[83]\"}",
                new ToolContext(Map.of()))).isInstanceOf(ToolExecutionException.class);
        recordSilentToolCall(worker, "retry-call", "{\"biz_ctx_list\":\"83\"}");
        recorded.call("{\"biz_ctx_list\":\"83\"}", new ToolContext(Map.of()));

        assertThat(events).noneMatch(event -> "guide".equals(event.subtype()));
    }

    @Test
    void emitsAValidatedContextSnapshotAndUsesTheEstimateFloorForBrokenProviderUsage() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(1L, 1L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiContextBudget budget = new AiContextBudget(
                "claude-fable-5", 200000L, 16000L, 150000L, 8192L, 32000L, false);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "claude-fable-5", "high",
                events::add, budget, 5000L);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, 4, 6)).build());

        recorder.recordModelResponse(response, "assistant");

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().metrics())
                .doesNotContainKey("prompt_tokens")
                .containsEntry("provider_reported_prompt_tokens", 2L)
                .containsEntry("prompt_tokens_complete", false)
                .containsEntry("context_input_tokens", 5000L)
                .containsEntry("context_estimated", true);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.subtype()).isEqualTo("context_usage");
            assertThat(event.metadata().get("contextUsage").toString())
                    .contains("currentInputTokens=5000", "estimated=true");
        });
    }

    @Test
    void forkedRecorderScopesMetricsAndDoesNotDriveTheConversationContextUsage() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(1L, 1L, Instant.now()));
        List<AiExecutionEvent> events = new ArrayList<>();
        AiContextBudget budget = new AiContextBudget(
                "model", 200000L, 16000L, 150000L, 8192L, 32000L, false);
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "model", "high",
                events::add, budget, 5000L);
        AiTrajectoryRecorder child = root.fork(Map.of(
                "fanout_id", "fanout-abc",
                "node_id", "fanout-abc-agent-01",
                "agent_name", "data-investigator",
                "depth", 1));
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, 4, 6)).build());

        child.recordModelResponse(response, "specialist");

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().metrics()).containsEntry("context_scope", "subagent");
        assertThat(events).isEmpty();
        assertThat(child.usageSnapshot().nodeId()).isEqualTo("fanout-abc-agent-01");
        assertThat(child.usageSnapshot().agentName()).isEqualTo("data-investigator");
        assertThat(child.usageSnapshot().promptTokens()).isZero();
        assertThat(child.usageSnapshot().completionTokens()).isEqualTo(4L);
        assertThat(child.usageSnapshot().modelCalls()).isEqualTo(1L);
    }

    @Test
    void disclosureSealAllowsOnlyBoundedLateUsageAccounting() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), mock(ScoreUser.class),
                "conversation-1", "request-1", "model", "high", ignored -> { });
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("late candidate"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, 4, 6)).build());

        recorder.sealAgainstLateCallbacks();
        recorder.recordModelResponse(response, "assistant");

        assertThat(recorder.usageSnapshot().completionTokens()).isEqualTo(4L);
        assertThat(recorder.usageSnapshot().modelCalls()).isEqualTo(1L);
        verify(repository, org.mockito.Mockito.never()).append(any(), any());

        recorder.sealUsageAccounting();
        recorder.recordModelResponse(response, "assistant");

        assertThat(recorder.usageSnapshot().completionTokens()).isEqualTo(4L);
        assertThat(recorder.usageSnapshot().modelCalls()).isEqualTo(1L);
    }

    @Test
    void recordFanOutUsageAggregatesChildSnapshotsIntoOneAuthoritativeStep() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiContextBudget budget = new AiContextBudget(
                "model", 200000L, 16000L, 150000L, 8192L, 32000L, false);
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "model", "high",
                events::add, budget, 5000L);

        root.recordFanOutUsage("fanout-abc", java.util.Arrays.asList(
                new AiUsageSnapshot("fanout-abc-lead", "lead", 100L, 10L, 1L),
                new AiUsageSnapshot("fanout-abc-agent-01", "data-investigator",
                        200L, 20L, 2L),
                null));

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().messageKind()).isEqualTo(AiTrajectoryRecorder.FANOUT_USAGE_STEP_KIND);
        assertThat(step.getValue().metrics())
                .containsEntry("fanout_prompt_tokens", 300L)
                .containsEntry("fanout_completion_tokens", 30L)
                .containsEntry("context_input_tokens", 5000L)
                .containsEntry("context_estimated", true)
                .doesNotContainKey("prompt_tokens")
                .doesNotContainKey("context_scope");
        assertThat(step.getValue().extra()).containsEntry("fanout_id", "fanout-abc");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.subtype()).isEqualTo("context_usage");
            assertThat(event.metadata().get("contextUsage").toString()).contains("fanout_settled");
        });
    }

    @Test
    void recordsParallelUsageAsParallelTrajectoryMetadata() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});

        root.recordFanOutUsage("fanout-parallel", "parallel", List.of(
                new AiUsageSnapshot(
                        "fanout-parallel-agent-01", "worker", 10L, 2L, 1L)));

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        assertThat(step.getValue().message()).isEqualTo("Parallel workflow usage settled.");
        assertThat(step.getValue().extra())
                .containsEntry("fanout_id", "fanout-parallel")
                .containsEntry("execution_kind", "parallel");
    }

    @Test
    void forkedRecorderSuppressesToolOutputContextUsageEvents() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiContextBudget budget = new AiContextBudget(
                "model", 200000L, 16000L, 150000L, 8192L, 32000L, false);
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", "model", "high",
                events::add, budget, 5000L);
        AiTrajectoryRecorder child = root.fork(Map.of(
                "fanout_id", "fanout-abc", "node_id", "fanout-abc-agent-01", "depth", 1));
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_libraries").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("{\"items\":[]}");

        child.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));

        assertThat(events).extracting(AiExecutionEvent::subtype)
                .doesNotContain("context_usage");
    }

    @Test
    void sealedRecorderIgnoresFanOutUsage() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> {});

        recorder.terminalLifecycle("multi_agent_failed", "Fan-out failed.", Map.of("status", "failed"));
        recorder.recordFanOutUsage("fanout-abc", List.of(
                new AiUsageSnapshot(null, null, 100L, 10L, 1L)));

        verify(repository, times(1)).append(eq("conversation-1"), any());
    }

    @Test
    void settledAccountingPersistsAfterTheDisclosureFenceCloses() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(), requester,
                "conversation-1", "request-1", ignored -> { });

        recorder.terminalLifecycle("workflow_timed_out", "Workflow timed out.",
                Map.of("status", "failed"));
        recorder.recordSettledFanOutUsage("fanout-late", "recursive_workflow", List.of(
                new AiUsageSnapshot("worker-1", "worker", 100L, 10L, 1L)));

        ArgumentCaptor<AiChatTrajectoryStep> steps =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(2)).append(eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues().getLast().messageKind())
                .isEqualTo(AiTrajectoryRecorder.FANOUT_USAGE_STEP_KIND);
        assertThat(steps.getAllValues().getLast().metrics())
                .containsEntry("fanout_prompt_tokens", 100L)
                .containsEntry("fanout_completion_tokens", 10L);
    }

    @Test
    void truncatesAdversarialUnicodeToolOutputAtAValidUtf8Boundary() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
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
                .containsExactly("started", "tool_output_truncated", "completed");
        assertThat(events.getLast().metadata()).containsEntry("result_truncated", true);
    }

    @Test
    void capsToolOutputByTheRemainingContextBudgetAcrossTheActiveToolLoop() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("get_large_result").description("test").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("x".repeat(1000));
        AiContextBudget budget = new AiContextBudget(
                "model", 120L, 10L, 90L, 10L, 100L, false);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                mock(ScoreUser.class), "conversation-1", "request-1", "model", "high",
                ignored -> {}, budget, 90L);

        String output = recorder.recordingTools(() -> new ToolCallback[]{callback}, 100L)
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));

        assertThat(output.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(10 * 3);
    }

    @Test
    void appliesTheRemainingContextBudgetToApprovedMutationResults() {
        AiContextBudget budget = new AiContextBudget(
                "model", 120L, 10L, 90L, 10L, 100L, false);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(mock(AiChatConversationRepository.class),
                new ObjectMapper(), mock(ScoreUser.class), "conversation-1", "request-1", "model",
                "high", events::add, budget, 90L);

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
        AiContextBudget budget = new AiContextBudget(
                "model", 120L, 10L, 90L, 10L, 100L, false);
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(mock(AiChatConversationRepository.class),
                new ObjectMapper(), mock(ScoreUser.class), "conversation-1", "request-1", "model",
                "high", ignored -> {}, budget, budget.safeInputLimit());

        assertThat(recorder.limitToolOutput("must not fit", 100L, "get_result")).isEmpty();
    }

    private Map<String, Object> recordedMetrics(String providerType, DefaultUsage usage) {
        return recordedMetrics(providerType, usage, 0L);
    }

    private Map<String, Object> recordedMetrics(
            String providerType, DefaultUsage usage, long estimatedInputFloor) {
        return recordedMetrics(providerType, usage, estimatedInputFloor, false);
    }

    private Map<String, Object> recordedMetrics(
            String providerType, DefaultUsage usage, long estimatedInputFloor,
            boolean streaming) {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        when(repository.append(eq("conversation-1"), any()))
                .thenReturn(new AiChatStoredStep(1L, 1L, Instant.now()));
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                repository, new ObjectMapper(), mock(ScoreUser.class),
                "conversation-1", "request-1", "model", "high",
                ignored -> {}, null, estimatedInputFloor);
        recorder.useModelProvider(providerType);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(usage).build());

        if (streaming) {
            recorder.recordStreamingModelResponse(response, "assistant");
        } else {
            recorder.recordModelResponse(response, "assistant");
        }

        ArgumentCaptor<AiChatTrajectoryStep> step =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository).append(eq("conversation-1"), step.capture());
        return step.getValue().metrics();
    }

    private static ToolExecutionException mcpValidationFailure(ToolDefinition definition) {
        return mcpFailure(definition,
                "biz_ctx_list must be a comma-separated list of integers.");
    }

    private static ToolExecutionException mcpFailure(ToolDefinition definition, String detail) {
        return new ToolExecutionException(definition, new IllegalStateException(
                "Error calling tool: [TextContent[annotations=null, text=" + detail
                        + ", meta=null]]"));
    }

    private static void recordSilentToolCall(AiTrajectoryRecorder recorder, String id,
                                             String arguments) {
        AssistantMessage.ToolCall retry = new AssistantMessage.ToolCall(
                id, "function", "create_top_level_asbiep", arguments);
        recorder.recordModelResponse(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().toolCalls(List.of(retry)).build()))), "assistant");
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
