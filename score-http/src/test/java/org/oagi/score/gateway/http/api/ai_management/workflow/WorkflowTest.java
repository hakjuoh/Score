package org.oagi.score.gateway.http.api.ai_management.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowAgent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTest {

    @Test
    void gatewayCanCompleteTheMainQueueWithoutCallingAnotherAgent() {
        List<String> calls = new ArrayList<>();
        WorkflowAgent gateway = agent("gateway-agent", calls,
                ignored -> complete("hello"));
        WorkflowAgent assistant = agent("connectcenter-assistant", calls,
                ignored -> complete("should-not-run"));
        Workflow workflow = workflow(gateway, assistant);
        AiChatExecutor.Context context = context();

        AiChatExecutor.Result result = workflow.execute(context);

        assertThat(result.answer()).isEqualTo("hello");
        assertThat(calls).containsExactly("gateway-agent");
        verify(context.recorder()).terminalLifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_completed"),
                any(), any());
    }

    @Test
    void agentsCanBuildAndExecuteARecursiveWorkflowThroughHandoffs() {
        List<String> calls = new ArrayList<>();
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("root-work", List.of(
                        member("first", "worker-one"),
                        new AiWorkflowPlan.Member("nested-member", null,
                                new AiWorkflowPlan.WorkflowDefinition("nested-work",
                                        List.of(member("second", "worker-two")))))),
                "Delegating.", "Synthesizing.");
        WorkflowAgent gateway = agent("gateway-agent", calls,
                ignored -> handoff("connectcenter-assistant"));
        WorkflowAgent assistant = agent("connectcenter-assistant", calls,
                ignored -> handoff("workflow-planner"));
        WorkflowAgent planner = agent("workflow-planner", calls,
                ignored -> new AgentDecision.Delegate(plan));
        WorkflowAgent first = agent("worker-one", calls,
                ignored -> complete("one"));
        WorkflowAgent second = agent("worker-two", calls,
                context -> {
                    assertThat(context.inputs()).extracting(WorkflowResult::output)
                            .containsExactly("one");
                    return complete("two");
                });
        WorkflowAgent synthesizer = agent("workflow-synthesizer", calls, context ->
                complete(context.workflow().root().id() + "="
                        + context.inputs().stream().map(WorkflowResult::output)
                        .reduce((left, right) -> left + "," + right).orElseThrow()));
        WorkflowAgent evaluator = agent("workflow-evaluator", calls,
                context -> new AgentDecision.Complete(context.candidate()));
        Workflow workflow = workflow(gateway, assistant, planner, first, second,
                synthesizer, evaluator);

        AiChatExecutor.Context execution = context();
        AiChatExecutor.Result result = workflow.execute(execution);

        assertThat(result.answer()).isEqualTo(
                "root-work=one,two");
        assertThat(calls).containsExactly(
                "gateway-agent", "connectcenter-assistant", "workflow-planner",
                "worker-one", "worker-two", "workflow-synthesizer",
                "workflow-evaluator");
        assertThat(result.traceMetadata())
                .containsEntry("workflow", "main")
                .containsEntry("workflow_iterations", 1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> namespaces =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(execution.recorder(), times(3)).fork(namespaces.capture());
        assertThat(namespaces.getAllValues().get(1))
                .containsEntry("node_id", "main:1:root-work")
                .containsEntry("parent_node_id", "main");
        assertThat(namespaces.getAllValues().getLast())
                .containsEntry("node_id", "main:1:root-work:nested-member")
                .containsEntry("parent_node_id", "main:1:root-work");
    }

    @Test
    void evaluatorCanReturnTheResultToThePlannerUntilItIsComplete() {
        List<String> calls = new ArrayList<>();
        AtomicInteger plans = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        WorkflowAgent gateway = agent("gateway-agent", calls,
                ignored -> handoff("connectcenter-assistant"));
        WorkflowAgent assistant = agent("connectcenter-assistant", calls,
                ignored -> handoff("workflow-planner"));
        WorkflowAgent planner = agent("workflow-planner", calls, ignored -> {
            int iteration = plans.incrementAndGet();
            return new AgentDecision.Delegate(plan("attempt-" + iteration, "worker"));
        });
        WorkflowAgent worker = agent("worker", calls,
                context -> complete("result-" + context.iteration()));
        WorkflowAgent synthesizer = agent("workflow-synthesizer", calls,
                context -> complete(context.inputs().getLast().output()));
        WorkflowAgent evaluator = agent("workflow-evaluator", calls, context -> {
            if (evaluations.incrementAndGet() == 1) {
                return new AgentDecision.Handoff(new Agent.AgentId("workflow-planner"),
                        new AiWorkflowFeedback(1, "attempt-1", context.candidate().answer(),
                                "The first attempt is incomplete.", "Finish the request."));
            }
            assertThat(context.feedback()).hasSize(1);
            return new AgentDecision.Complete(context.candidate());
        });

        AiChatExecutor.Result result = workflow(gateway, assistant, planner, worker,
                synthesizer, evaluator).execute(context());

        assertThat(result.answer()).isEqualTo("result-2");
        assertThat(plans).hasValue(2);
        assertThat(evaluations).hasValue(2);
    }

    @Test
    void childWorkflowFailsWhenEveryMemberFails() {
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("connectcenter-assistant"));
        WorkflowAgent assistant = agent("connectcenter-assistant", new ArrayList<>(),
                ignored -> handoff("workflow-planner"));
        WorkflowAgent planner = agent("workflow-planner", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan("failed-work", "worker")));
        WorkflowAgent worker = agent("worker", new ArrayList<>(),
                ignored -> { throw new IllegalStateException("boom"); });

        assertThatThrownBy(() -> workflow(gateway, assistant, planner, worker)
                .execute(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Every member");
    }

    @Test
    void engineRejectsADelegatedPlanThatExceedsTheRequestAgentLimit() {
        List<AiWorkflowPlan.Member> members = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> member("member-" + index, "worker"))
                .toList();
        AiWorkflowPlan oversized = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("oversized", members), null, null);
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(oversized));
        WorkflowAgent worker = agent("worker", new ArrayList<>(),
                ignored -> complete("unused"));

        assertThatThrownBy(() -> workflow(gateway, worker).execute(context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent call limit");
    }

    @Test
    void cancellationIsTerminalInsteadOfBecomingAMemberFailure() {
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> { throw new CancellationException("stopped"); });
        AiChatExecutor.Context execution = context();

        assertThatThrownBy(() -> workflow(gateway).execute(execution))
                .isInstanceOf(CancellationException.class);
        verify(execution.recorder()).terminalLifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_cancelled"), any(), any());
    }

    @Test
    void policyRefusalStopsTheWorkflowInsteadOfBecomingAPartialFailure() {
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("guarded", List.of(
                        member("first", "refusing-worker"),
                        member("second", "later-worker"))), null, null);
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan));
        WorkflowAgent refusing = agent("refusing-worker", new ArrayList<>(), ignored -> {
            throw new AgentInputRefusedException(new GuardrailRefusal(
                    GuardrailDecision.of("worker-policy", "1",
                            GuardrailDecision.Action.REFUSE),
                    "WORKER_POLICY_REFUSAL", "ai.policy.refused"));
        });
        List<String> laterCalls = new ArrayList<>();
        WorkflowAgent later = agent("later-worker", laterCalls,
                ignored -> complete("must-not-run"));

        AiChatExecutor.Context execution = context();
        assertThatThrownBy(() -> workflow(gateway, refusing, later).execute(execution))
                .isInstanceOf(AgentInputRefusedException.class);
        assertThat(laterCalls).isEmpty();
        verify(execution.recorder(), times(2)).terminalLifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_refused"), any(), any());
    }

    @Test
    void workflowDefinitionRejectsDuplicateQueueMemberIds() {
        assertThatThrownBy(() -> new AiWorkflowPlan.WorkflowDefinition("duplicate",
                List.of(member("same", "worker-one"), member("same", "worker-two"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate Workflow member id");
    }

    @Test
    void oneRequestBudgetStopsAnUnboundedHandoffCycle() {
        WorkflowAgent cyclingGateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("gateway-agent"));

        assertThatThrownBy(() -> workflow(cyclingGateway).execute(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution budget");
    }

    @Test
    void absoluteDeadlineInterruptsABlockedAgent() {
        WorkflowAgent blocked = agent("gateway-agent", new ArrayList<>(), ignored -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException("stopped");
            }
            return complete("late");
        });
        Workflow workflow = new Workflow(mock(AiChatExecutor.class), null, null, null,
                3, Duration.ofMillis(30), List.of(blocked));

        assertThatThrownBy(() -> workflow.execute(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    void deadlineFencesLateCallbacksAndSettlesUsageExactlyOnce() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                null, "conversation-1", "request-1", "model", "high", events::add);
        AiChatExecutor.Context execution = context();
        when(execution.recorder()).thenReturn(root);
        WorkflowAgent late = agent("gateway-agent", new ArrayList<>(), context -> {
            AiTrajectoryRecorder callbackRecorder = root.fork(Map.of("node_id", "late"));
            context.registerUsage(callbackRecorder::usageSnapshot,
                    callbackRecorder::sealAgainstLateCallbacks);
            context.recordUsage(new AiUsageSnapshot("late", "Late Agent", 3, 2, 1));
            long finish = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            while (System.nanoTime() < finish) {
                Thread.interrupted();
                Thread.onSpinWait();
            }
            callbackRecorder.progress("late callback");
            context.recordUsage(new AiUsageSnapshot("too-late", "Late Agent", 999, 999, 1));
            return complete("late answer");
        });
        Workflow workflow = new Workflow(mock(AiChatExecutor.class), null, null, null,
                3, Duration.ofMillis(30), List.of(late));

        assertThatThrownBy(() -> workflow.execute(execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");

        assertThat(events).extracting(AiExecutionEvent::content)
                .doesNotContain("late callback");
        var steps = org.mockito.ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(3)).append(
                org.mockito.ArgumentMatchers.eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).filteredOn(
                        step -> "fanout_usage".equals(step.messageKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.metrics())
                        .containsEntry("fanout_prompt_tokens", 3L)
                        .containsEntry("fanout_completion_tokens", 2L));
    }

    @Test
    void controlPlaneAgentCannotBeSelectedAsAPlanWorker() {
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(plan("invalid", "workflow-evaluator")));
        WorkflowAgent evaluator = agent("workflow-evaluator", new ArrayList<>(),
                ignored -> complete("must-not-run"));

        assertThatThrownBy(() -> workflow(gateway, evaluator).execute(context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not assignable");
    }

    @Test
    void engineReducesToolAuthorityForEveryAssignableAgent() {
        AiChatExecutor.Context root = context();
        AiChatExecutor.Context restricted = mock(AiChatExecutor.Context.class);
        when(root.forWorkflowAssignment(AiChatExecutor.ToolPolicy.NONE, "worker"))
                .thenReturn(restricted);
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(plan("restricted", "worker")));
        WorkflowAgent worker = agent("worker", new ArrayList<>(), context -> {
            assertThat(context.execution()).isSameAs(restricted);
            return complete("safe");
        });

        assertThat(workflow(gateway, worker).execute(root).answer()).isEqualTo("safe");
    }

    @Test
    void failedReplanRetainsTheLastSuccessfulCandidate() {
        AtomicInteger planCalls = new AtomicInteger();
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("workflow-planner"));
        WorkflowAgent planner = agent("workflow-planner", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(planCalls.incrementAndGet() == 1
                        ? plan("first-attempt", "successful-worker")
                        : plan("second-attempt", "failed-worker")));
        WorkflowAgent successful = agent("successful-worker", new ArrayList<>(),
                ignored -> complete("last good answer"));
        WorkflowAgent failed = agent("failed-worker", new ArrayList<>(), ignored -> {
            throw new IllegalStateException("retry failed");
        });
        WorkflowAgent evaluator = agent("workflow-evaluator", new ArrayList<>(), context ->
                new AgentDecision.Handoff(new Agent.AgentId("workflow-planner"),
                        new AiWorkflowFeedback(1, "first-attempt",
                                context.candidate().answer(), "try again", "improve")));

        AiChatExecutor.Result result = workflow(gateway, planner, successful, failed, evaluator)
                .execute(context());

        assertThat(result.answer()).isEqualTo("last good answer");
        assertThat(result.traceMetadata())
                .containsEntry("evaluation_status", "iteration_failed")
                .containsEntry("failed_iteration", 2);
    }

    @Test
    void acceptedTurnIsTheOnlyPromptVisibleToDownstreamAgents() {
        AiChatExecutor.Context execution = context();
        when(execution.request().prompt()).thenReturn("raw secret");
        when(execution.userMessage()).thenReturn(new UserMessage("rewritten safe input"));
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("workflow-planner"));
        WorkflowAgent planner = agent("workflow-planner", new ArrayList<>(), context -> {
            assertThat(context.request().prompt()).isEqualTo("rewritten safe input");
            return new AgentDecision.Delegate(plan("accepted", "worker"));
        });
        WorkflowAgent worker = agent("worker", new ArrayList<>(),
                ignored -> complete("done"));

        assertThat(workflow(gateway, planner, worker).execute(execution).answer())
                .isEqualTo("done");
    }

    @Test
    void mainWorkflowKeepsTheRootRecorderOpenAndSettlesUsageOnce() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                null, "conversation-1", "request-1", "model", "high", events::add);
        AiChatExecutor.Context execution = context();
        when(execution.recorder()).thenReturn(root);
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(), context -> {
            context.recordUsage(new AiUsageSnapshot(
                    "main:gateway", "Gateway", 11, 7, 1));
            return complete("answer");
        });

        workflow(gateway).execute(execution);
        root.contentDelta("safe visible answer");

        assertThat(events).extracting(AiExecutionEvent::subtype)
                .contains("workflow_completed", "content_delta");
        var steps = org.mockito.ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, times(3)).append(
                org.mockito.ArgumentMatchers.eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).extracting(AiChatTrajectoryStep::messageKind)
                .containsExactly("agent_lifecycle", "fanout_usage", "agent_lifecycle");
    }

    @Test
    void partialFailureIsDisclosedToSynthesisAndFinalMetadata() {
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("partial", List.of(
                        member("completed", "successful-worker"),
                        member("failed", "failed-worker"))), null, null);
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan));
        WorkflowAgent successful = agent("successful-worker", new ArrayList<>(),
                ignored -> complete("mutation committed"));
        WorkflowAgent failed = agent("failed-worker", new ArrayList<>(), ignored -> {
            throw new IllegalStateException("follow-up failed");
        });
        WorkflowAgent synthesizer = agent("workflow-synthesizer", new ArrayList<>(), context -> {
            assertThat(context.inputs()).extracting(WorkflowResult::successful)
                    .containsExactly(true, false);
            return complete("mutation committed");
        });

        AiChatExecutor.Result result = workflow(
                gateway, successful, failed, synthesizer).execute(context());

        assertThat(result.answer()).contains("mutation committed",
                "Some requested steps could not be completed",
                "may already have taken effect");
        assertThat(result.traceMetadata())
                .containsEntry("completed", 1)
                .containsEntry("failed", 1)
                .containsEntry("partial_failure", true)
                .containsEntry("partial_failure_notice", true);
    }

    @Test
    void nestedPartialFailureCannotBeErasedByAnOuterSynthesizer() {
        AiWorkflowPlan.WorkflowDefinition inner = new AiWorkflowPlan.WorkflowDefinition(
                "inner", List.of(member("inner-success", "successful-worker"),
                member("inner-failure", "failed-worker")));
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("outer", List.of(
                        new AiWorkflowPlan.Member("inner-member", null, inner),
                        member("outer-success", "successful-worker"))), null, null);
        WorkflowAgent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan));
        WorkflowAgent successful = agent("successful-worker", new ArrayList<>(),
                ignored -> complete("completed"));
        WorkflowAgent failed = agent("failed-worker", new ArrayList<>(), ignored -> {
            throw new IllegalStateException("nested failure");
        });
        WorkflowAgent synthesizer = agent("workflow-synthesizer", new ArrayList<>(),
                context -> complete(context.workflow().root().id() + " synthesized"));

        AiChatExecutor.Result result = workflow(
                gateway, successful, failed, synthesizer).execute(context());

        assertThat(result.answer()).contains("outer synthesized",
                "Some requested steps could not be completed",
                "may already have taken effect");
        assertThat(result.traceMetadata())
                .containsEntry("completed", 2)
                .containsEntry("direct_failed", 0)
                .containsEntry("failed", 1)
                .containsEntry("partial_failure", true)
                .containsEntry("partial_failure_count", 1)
                .containsEntry("partial_failure_notice", true);
    }

    private Workflow workflow(WorkflowAgent... agents) {
        return new Workflow(mock(AiChatExecutor.class), null, null, null,
                3, List.of(agents));
    }

    private WorkflowAgent agent(String id, List<String> calls,
                                Function<AgentWorkflowContext, AgentDecision> operation) {
        return new WorkflowAgent() {
            private final AgentDefinition definition = new AgentDefinition(
                    new Agent.AgentId(id), id, id + " description",
                    new AgentDefinition.InstructionTemplate("instruction"));

            @Override
            public AgentDefinition definition() {
                return definition;
            }

            @Override
            public boolean assignable() {
                return !java.util.Set.of("gateway-agent", "connectcenter-assistant",
                        "workflow-planner", "workflow-evaluator",
                        "workflow-synthesizer").contains(id);
            }

            @Override
            public AgentDecision execute(AgentWorkflowContext context) {
                calls.add(id);
                return operation.apply(context);
            }
        };
    }

    private AgentDecision.Complete complete(String answer) {
        return new AgentDecision.Complete(new AiChatExecutor.Result(answer, Map.of()));
    }

    private AgentDecision.Handoff handoff(String id) {
        return new AgentDecision.Handoff(new Agent.AgentId(id));
    }

    private AiWorkflowPlan plan(String id, String agentId) {
        return new AiWorkflowPlan(new AiWorkflowPlan.WorkflowDefinition(id,
                List.of(member("member", agentId))), null, null);
    }

    private AiWorkflowPlan.Member member(String id, String agentId) {
        return new AiWorkflowPlan.Member(id,
                new AiWorkflowPlan.AgentTask(agentId, id, "Do " + id,
                        null, "Working", "Completed",
                        AiWorkflowPlan.ToolAccess.NONE), null);
    }

    private AiChatExecutor.Context context() {
        AiChatExecutor.Context context = mock(AiChatExecutor.Context.class);
        ChatRequest request = mock(ChatRequest.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(context.agentDepth()).thenReturn(0);
        when(context.request()).thenReturn(request);
        when(context.recorder()).thenReturn(recorder);
        when(context.forWorkflowAssignment(any(), any())).thenReturn(context);
        when(context.userMessage()).thenReturn(new UserMessage("accepted prompt"));
        when(request.requestId()).thenReturn("request-1");
        when(request.conversationId()).thenReturn("conversation-1");
        when(request.multiAgent()).thenReturn(
                new AiMultiAgentOptions(true, 4, "balanced"));
        when(recorder.fork(any())).thenReturn(recorder);
        return context;
    }
}
