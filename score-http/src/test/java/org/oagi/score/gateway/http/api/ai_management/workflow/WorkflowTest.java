package org.oagi.score.gateway.http.api.ai_management.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentFailure;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrails;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRequestHandler;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionRecorderAdapter;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.support.TestAgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
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
        Agent gateway = agent("gateway-agent", calls,
                ignored -> complete("hello"));
        Agent assistant = agent("connectcenter-assistant", calls,
                ignored -> complete("should-not-run"));
        WorkflowRunner workflow = workflow(gateway, assistant);
        AgentExecutionContext context = context();

        AgentOutput result = workflow.execute(workflowContext(context));

        assertThat(result.content()).isEqualTo("hello");
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
        Agent gateway = agent("gateway-agent", calls,
                ignored -> handoff("connectcenter-assistant"));
        Agent assistant = agent("connectcenter-assistant", calls,
                ignored -> handoff("workflow-planner"));
        Agent planner = agent("workflow-planner", calls,
                ignored -> new AgentDecision.Delegate(plan));
        Agent first = agent("worker-one", calls,
                ignored -> complete("one"));
        Agent second = agent("worker-two", calls,
                context -> {
                    assertThat(context.inputs()).extracting(WorkflowResult::output)
                            .containsExactly("one");
                    return complete("two");
                });
        Agent synthesizer = agent("workflow-synthesizer", calls, context ->
                complete(context.workflow().root().id() + "="
                        + context.inputs().stream().map(WorkflowResult::output)
                        .reduce((left, right) -> left + "," + right).orElseThrow()));
        Agent evaluator = agent("workflow-evaluator", calls,
                context -> new AgentDecision.Complete(context.candidate()));
        WorkflowRunner workflow = workflow(gateway, assistant, planner, first, second,
                synthesizer, evaluator);

        AgentExecutionContext execution = context();
        AgentOutput result = workflow.execute(workflowContext(execution));

        assertThat(result.content()).isEqualTo(
                "root-work=one,two");
        assertThat(calls).containsExactly(
                "gateway-agent", "connectcenter-assistant", "workflow-planner",
                "worker-one", "worker-two", "workflow-synthesizer",
                "workflow-evaluator");
        assertThat(result.metadata())
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
    void dependencyEdgesFanOutReadyAgentsAndJoinTheirResultsInDeclarationOrder()
            throws InterruptedException {
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        Agent gateway = agent("gateway-agent", calls, ignored -> new AgentDecision.Delegate(
                new AiWorkflowPlan(new AiWorkflowPlan.WorkflowDefinition("parallel", List.of(
                        member("first", "worker-one"),
                        member("second", "worker-two"),
                        member("join", "join-agent")), List.of(
                        new AiWorkflowPlan.Edge("second", "join"),
                        new AiWorkflowPlan.Edge("first", "join"))), null, null)));
        Agent first = agent("worker-one", calls, ignored -> {
            bothStarted.countDown();
            await(bothStarted);
            return complete("one");
        });
        Agent second = agent("worker-two", calls, ignored -> {
            bothStarted.countDown();
            await(bothStarted);
            return complete("two");
        });
        Agent join = agent("join-agent", calls, context -> {
            assertThat(context.inputs()).extracting(WorkflowResult::output)
                    .containsExactly("one", "two");
            return complete("joined");
        });

        AgentOutput result = workflow(gateway, first, second, join)
                .execute(workflowContext(context()));

        assertThat(result.content()).isEqualTo("joined");
        assertThat(calls.indexOf("join-agent"))
                .isGreaterThan(calls.indexOf("worker-one"))
                .isGreaterThan(calls.indexOf("worker-two"));
    }

    @Test
    void terminalFailureInAReadyLayerCancelsItsRunningSibling() {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerInterrupted = new CountDownLatch(1);
        List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        Agent gateway = agent("gateway-agent", calls, ignored -> new AgentDecision.Delegate(
                new AiWorkflowPlan(new AiWorkflowPlan.WorkflowDefinition("parallel", List.of(
                        member("blocked", "blocked-agent"),
                        member("cancelled", "cancelled-agent")), List.of()), null, null)));
        Agent blocked = agent("blocked-agent", calls, ignored -> {
            blockerStarted.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("The sibling should have been interrupted.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                blockerInterrupted.countDown();
                throw new java.util.concurrent.CancellationException("sibling interrupted");
            }
        });
        Agent cancelled = agent("cancelled-agent", calls, ignored -> {
            await(blockerStarted);
            throw new java.util.concurrent.CancellationException("terminal cancellation");
        });

        assertThatThrownBy(() -> workflow(gateway, blocked, cancelled)
                .execute(workflowContext(context())))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        await(blockerInterrupted);
    }

    @Test
    void workflowGraphRejectsUnknownAndCyclicEdges() {
        List<AiWorkflowPlan.Member> members = List.of(
                member("first", "worker-one"), member("second", "worker-two"));

        assertThatThrownBy(() -> new AiWorkflowPlan.WorkflowDefinition(
                "unknown", members, List.of(new AiWorkflowPlan.Edge("missing", "second"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown member");
        assertThatThrownBy(() -> new AiWorkflowPlan.WorkflowDefinition(
                "cycle", members, List.of(new AiWorkflowPlan.Edge("first", "second"),
                new AiWorkflowPlan.Edge("second", "first"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void evaluatorCanReturnTheResultToThePlannerUntilItIsComplete() {
        List<String> calls = new ArrayList<>();
        AtomicInteger plans = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Agent gateway = agent("gateway-agent", calls,
                ignored -> handoff("connectcenter-assistant"));
        Agent assistant = agent("connectcenter-assistant", calls,
                ignored -> handoff("workflow-planner"));
        Agent planner = agent("workflow-planner", calls, ignored -> {
            int iteration = plans.incrementAndGet();
            return new AgentDecision.Delegate(plan("attempt-" + iteration, "worker"));
        });
        Agent worker = agent("worker", calls,
                context -> complete("result-" + context.iteration()));
        Agent synthesizer = agent("workflow-synthesizer", calls,
                context -> complete(context.inputs().getLast().output()));
        Agent evaluator = agent("workflow-evaluator", calls, context -> {
            if (evaluations.incrementAndGet() == 1) {
                return new AgentDecision.Handoff(new Agent.AgentId("workflow-planner"),
                        new AiWorkflowFeedback(1, "attempt-1", context.candidate().content(),
                                "The first attempt is incomplete.", "Finish the request."));
            }
            assertThat(context.feedback()).hasSize(1);
            return new AgentDecision.Complete(context.candidate());
        });

        AgentOutput result = workflow(gateway, assistant, planner, worker,
                synthesizer, evaluator).execute(workflowContext(context()));

        assertThat(result.content()).isEqualTo("result-2");
        assertThat(plans).hasValue(2);
        assertThat(evaluations).hasValue(2);
    }

    @Test
    void childWorkflowFailsWhenEveryMemberFails() {
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("connectcenter-assistant"));
        Agent assistant = agent("connectcenter-assistant", new ArrayList<>(),
                ignored -> handoff("workflow-planner"));
        Agent planner = agent("workflow-planner", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan("failed-work", "worker")));
        Agent worker = agent("worker", new ArrayList<>(),
                ignored -> { throw new IllegalStateException("boom"); });

        assertThatThrownBy(() -> workflow(gateway, assistant, planner, worker)
                .execute(workflowContext(context())))
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
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(oversized));
        Agent worker = agent("worker", new ArrayList<>(),
                ignored -> complete("unused"));

        assertThatThrownBy(() -> workflow(gateway, worker).execute(workflowContext(context())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent call limit");
    }

    @Test
    void cancellationIsTerminalInsteadOfBecomingAMemberFailure() {
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> { throw new CancellationException("stopped"); });
        AgentExecutionContext execution = context();

        assertThatThrownBy(() -> workflow(gateway).execute(workflowContext(execution)))
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
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan));
        Agent refusing = agent("refusing-worker", new ArrayList<>(), ignored -> {
            throw new AgentInputRefusedException(new GuardrailRefusal(
                    GuardrailDecision.of("worker-policy", "1",
                            GuardrailDecision.Action.REFUSE),
                    "WORKER_POLICY_REFUSAL", "ai.policy.refused"));
        });
        List<String> laterCalls = new ArrayList<>();
        Agent later = agent("later-worker", laterCalls,
                ignored -> complete("must-not-run"));

        AgentExecutionContext execution = context();
        assertThatThrownBy(() -> workflow(gateway, refusing, later).execute(workflowContext(execution)))
                .isInstanceOf(AgentInputRefusedException.class);
        assertThat(laterCalls).isEmpty();
        verify(execution.recorder(), times(2)).terminalLifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_refused"), any(), any());
        verify(execution.recorder(), org.mockito.Mockito.never()).sealAgainstLateCallbacks();
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
        Agent cyclingGateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("gateway-agent"));

        assertThatThrownBy(() -> workflow(cyclingGateway).execute(workflowContext(context())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution budget");
    }

    @Test
    void inactivityLeaseInterruptsABlockedRootAgent() {
        Agent blocked = agent("gateway-agent", new ArrayList<>(), ignored -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException("stopped");
            }
            return complete("late");
        });
        WorkflowRunner workflow = new WorkflowRunner(
                new AgentRunner(null, List.of(blocked)), null, 3, Duration.ofMillis(30));

        assertThatThrownBy(() -> workflow.execute(workflowContext(context())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
    }

    @Test
    void stalledChildMemberDoesNotStopAnActiveSiblingOrPartialSynthesis() {
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(new AiWorkflowPlan(
                        new AiWorkflowPlan.WorkflowDefinition("partial-work", List.of(
                                member("blocked", "blocked-worker"),
                                member("active", "active-worker")), List.of()), null, null)));
        Agent blocked = agent("blocked-worker", new ArrayList<>(), ignored -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException("stopped");
            }
            return complete("late");
        });
        Agent active = agent("active-worker", new ArrayList<>(), context -> {
            for (int heartbeat = 0; heartbeat < 6; heartbeat++) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("active worker interrupted");
                }
                context.progress();
            }
            return complete("active");
        });
        WorkflowRunner workflow = new WorkflowRunner(
                new AgentRunner(null, List.of(gateway, blocked, active)), null, 3,
                Duration.ofMillis(100));

        AgentOutput result = workflow.execute(workflowContext(context()));

        assertThat(result.content()).contains("active")
                .contains("Some requested steps could not be completed.");
        assertThat(result.metadata())
                .containsEntry("partial_failure", true)
                .containsEntry("partial_failure_count", 1);
    }

    @Test
    void inactivityLeaseRemainsTerminalWhenAnAgentRequestHandlerStalls() {
        AgentResponseHandler recovery = new AgentResponseHandler() {
            @Override
            public AgentDecision handle(
                    org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseContext response) {
                return complete("completed");
            }

            @Override
            public AgentDecision onFailure(AgentFailure failure) {
                return complete("recovered");
            }
        };
        AgentDefinition definition = new AgentDefinition(
                new Agent.AgentId("deadline-agent"), "deadline-agent", "deadline-agent",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    context.checkpoint();
                    return new AgentRunRequest.Skip(complete("late"));
                },
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                recovery, AgentGuardrails.none(), false);
        Agent agent = new DefinedAgent(definition);
        WorkflowRunBudget budget = new WorkflowRunBudget("request", mock(AgentExecutionRecorder.class),
                Duration.ofMillis(20), () -> { });
        AgentWorkflowContext context = workflowContext(context()).withRunControl(budget);

        assertThatThrownBy(() -> budget.invoke(
                new AgentRunner(null, List.of(agent)), agent.callId(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
    }

    @Test
    void observableProgressRenewsTheLeaseBeyondItsOriginalWallClockDuration() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        WorkflowRunBudget budget = new WorkflowRunBudget("request",
                AgentExecutionRecorder.noop(), Duration.ofNanos(10), () -> { }, clock::get);
        Agent active = agent("active", new ArrayList<>(), context -> {
            for (int step = 0; step < 4; step++) {
                clock.addAndGet(9L);
                context.progress();
            }
            return complete("finished after 36ns");
        });

        AgentDecision decision = budget.invoke(new AgentRunner(null, List.of(active)),
                active.callId(), workflowContext(context()).withRunControl(budget));

        assertThat(decision).isInstanceOfSatisfying(AgentDecision.Complete.class,
                complete -> assertThat(complete.result().content())
                        .isEqualTo("finished after 36ns"));
    }

    @Test
    void oneFailingLateWriteFenceDoesNotPreventTheRemainingFences() {
        AtomicInteger applied = new AtomicInteger();
        AgentInvocationLease lease = new AgentInvocationLease("worker", 10L,
                System::nanoTime,
                org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl.NOOP);
        lease.registerUsage(() -> null, () -> {
            throw new IllegalStateException("broken fence");
        });
        lease.registerUsage(() -> null, applied::incrementAndGet);

        lease.stop();

        assertThat(applied).hasValue(1);
    }

    @Test
    void stalledRootSignalsTheRequestRegistryAndTerminalRecorder() {
        org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry requests =
                mock(org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry.class);
        Agent blocked = agent("gateway-agent", new ArrayList<>(), ignored -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException("stopped");
            }
            return complete("late");
        });
        AgentExecutionContext execution = context();
        WorkflowRunner workflow = new WorkflowRunner(
                new AgentRunner(null, List.of(blocked)), requests, 3,
                Duration.ofMillis(30));

        assertThatThrownBy(() -> workflow.execute(workflowContext(execution)))
                .isInstanceOf(AgentInvocationStalledException.class);

        verify(requests).timeoutExecution("request-1");
        verify(execution.recorder()).sealAgainstLateCallbacks();
        verify(execution.recorder()).terminalLifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_stalled"), any(), any());
    }

    @Test
    void stalledRunFencesLateCallbacksAndSettlesUsageExactlyOnce() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        CountDownLatch usageSettled = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            AiChatTrajectoryStep step = invocation.getArgument(1);
            if (AiTrajectoryRecorder.FANOUT_USAGE_STEP_KIND.equals(step.messageKind())) {
                usageSettled.countDown();
            }
            return null;
        }).when(repository).append(any(), any());
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                null, "conversation-1", "request-1", "model", "high", events::add);
        AgentExecutionContext execution = context(root, "accepted prompt");
        Agent gateway = agent("gateway-agent", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(plan("late-work", "late-worker")));
        Agent late = agent("late-worker", new ArrayList<>(), context -> {
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
        WorkflowRunner workflow = new WorkflowRunner(
                new AgentRunner(null, List.of(gateway, late)), null, 3,
                Duration.ofMillis(30));

        assertThatThrownBy(() -> workflow.execute(workflowContext(execution)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Every member");
        await(usageSettled);

        assertThat(events).extracting(AiExecutionEvent::content)
                .doesNotContain("late callback");
        var steps = org.mockito.ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).append(
                org.mockito.ArgumentMatchers.eq("conversation-1"), steps.capture());
        assertThat(steps.getAllValues()).filteredOn(
                        step -> "fanout_usage".equals(step.messageKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.metrics())
                        .containsEntry("fanout_prompt_tokens", 3L)
                        .containsEntry("fanout_completion_tokens", 2L));
    }

    @Test
    void cancellationBeforeWorkerAdmissionCannotBlockUsageSettlement()
            throws InterruptedException {
        AgentExecutionRecorder root = mock(AgentExecutionRecorder.class);
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch releaseWorker = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> worker =
                new java.util.concurrent.atomic.AtomicReference<>();
        WorkflowRunBudget budget = new WorkflowRunBudget("request", root,
                Duration.ofSeconds(1), () -> { }, clock::get,
                invocation -> {
                    Thread delayed = Thread.startVirtualThread(() -> {
                        while (releaseWorker.getCount() > 0) {
                            try {
                                releaseWorker.await();
                            } catch (InterruptedException ignored) {
                                // Keep the FutureTask unstarted until the test releases it.
                            }
                        }
                        invocation.run();
                    });
                    worker.set(delayed);
                    clock.set(TimeUnit.SECONDS.toNanos(2));
                    return delayed;
                });
        budget.recordUsage(new AiUsageSnapshot("completed", "Completed", 5, 2, 1));
        Agent neverStarted = agent("never-started", new ArrayList<>(),
                ignored -> complete("must not run"));

        assertThatThrownBy(() -> budget.invoke(
                new AgentRunner(null, List.of(neverStarted)),
                neverStarted.callId(), workflowContext(context()).withRunControl(budget)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
        budget.settleUsage();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiUsageSnapshot>> usage =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(root).recordSettledFanOutUsage(any(), any(), usage.capture());
        assertThat(usage.getValue()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.promptTokens()).isEqualTo(5);
            assertThat(snapshot.completionTokens()).isEqualTo(2);
        });
        releaseWorker.countDown();
        worker.get().join(1_000);
        assertThat(worker.get().isAlive()).isFalse();
    }

    @Test
    void inactivityArithmeticSupportsNegativeNanoTimeReadings() {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(-100L);
        WorkflowRunBudget budget = new WorkflowRunBudget("request",
                AgentExecutionRecorder.noop(), Duration.ofNanos(10),
                () -> { }, clock::get);
        Agent stalled = agent("stalled", new ArrayList<>(), context -> {
            clock.set(-89L);
            context.checkpoint();
            return complete("late");
        });

        assertThatThrownBy(() -> budget.invoke(new AgentRunner(null, List.of(stalled)),
                stalled.callId(), workflowContext(context()).withRunControl(budget)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
    }

    @Test
    void stalledRunSettlementIncludesAnAdmittedModelAttempt() {
        AgentExecutionRecorder root = mock(AgentExecutionRecorder.class);
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong();
        WorkflowRunBudget budget = new WorkflowRunBudget("request", root,
                Duration.ofSeconds(1), () -> { }, clock::get);
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        AiModel model = new AiModel(new AiModel.ModelId("model"),
                new AiModel.ProviderId("provider"),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
        when(models.require("model")).thenReturn(model);
        var execution = TestAgentExecutionService.model(invocation -> {
            // Expire only after the model port has definitely admitted this call.
            clock.set(TimeUnit.SECONDS.toNanos(2));
            while (!Thread.currentThread().isInterrupted()) Thread.onSpinWait();
            Thread.interrupted();
            return new AgentRunResult(new AiMessage.Assistant("boundary answer"), List.of(),
                    java.util.Optional.of(new AgentRunResult.Usage(7, 3, 1)),
                    new AgentRunResult.RunMetadata(invocation.session().agent().id(),
                            model.id(), null, Map.of()));
        });
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("boundary-agent"), "Boundary Agent",
                "Returns only after cancellation interrupts its admitted model call.",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Model("model",
                        new Agent.Instruction("instruction"), new AiMessage.User("prompt"),
                        List.of(), context.executionScope(
                        org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE),
                        Map.of()),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), false));
        AgentRunner runner = new AgentRunner(execution, models, null, null, List.of(agent));
        AgentWorkflowContext context = workflowContext(context()).withRunControl(budget);

        assertThatThrownBy(() -> budget.invoke(runner, agent.callId(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
        budget.settleUsage();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiUsageSnapshot>> usage =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(root).recordSettledFanOutUsage(any(), any(), usage.capture());
        assertThat(usage.getValue()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.promptTokens()).isEqualTo(7);
            assertThat(snapshot.completionTokens()).isEqualTo(3);
            assertThat(snapshot.modelCalls()).isEqualTo(1);
        });
    }

    @Test
    void stalledRunKeepsAnAdmittedAssignedChatRecorderOpenUntilUsageIsPublished() {
        AgentExecutionRecorder root = mock(AgentExecutionRecorder.class);
        AgentExecutionRecorder child = mock(AgentExecutionRecorder.class);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch usageSettled = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            usageSettled.countDown();
            return null;
        }).when(root).recordSettledFanOutUsage(any(), any(), any());
        when(child.conversationId()).thenReturn("child-conversation");
        AgentExecutionContext childExecution = mock(AgentExecutionContext.class);
        when(childExecution.modelName()).thenReturn("model");
        when(childExecution.recorder()).thenReturn(child);
        when(childExecution.executionPurpose()).thenReturn(
                org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER);
        when(childExecution.withAgentIdentity(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(
                        org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.class)))
                .thenReturn(childExecution);
        when(childExecution.withToolBinding(any())).thenReturn(childExecution);
        when(childExecution.finalizeInstruction(any())).thenAnswer(
                invocation -> invocation.getArgument(0));
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong();
        WorkflowRunBudget budget = new WorkflowRunBudget("request", root,
                Duration.ofSeconds(1), () -> { }, clock::get);
        var execution = TestAgentExecutionService.chat(session -> {
            session.context().recorder().verifyActive();
            clock.set(TimeUnit.SECONDS.toNanos(2));
            boolean released = false;
            while (!released) {
                try {
                    released = releaseProvider.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a provider call that cannot be cancelled once admitted.
                }
            }
            session.context().recorder().verifyActive();
            return new org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult(
                    "boundary chat", Map.of(),
                    java.util.Optional.of(new AgentRunResult.Usage(9, 4, 1)));
        });
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("chat-boundary-agent"), "Chat Boundary Agent",
                "Publishes usage after an admitted Chat call returns.",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Chat(
                        childExecution, new Agent.Instruction("instruction")),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), true));
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                agent.id().value(), "Boundary", "Run boundary chat.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(new AiWorkflowPlan.WorkflowDefinition(
                "chat-boundary", List.of(new AiWorkflowPlan.Member("member", task, null))),
                null, null);
        AgentWorkflowContext context = workflowContext(context()).withRunControl(budget)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "chat-boundary", "main:chat-boundary", "main", 1))
                .withAssignment(plan, "member", task, List.of());

        assertThatThrownBy(() -> budget.invoke(
                new AgentRunner(execution, List.of(agent)), agent.callId(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
        budget.settleUsage();

        verify(root, org.mockito.Mockito.never())
                .recordSettledFanOutUsage(any(), any(), any());
        verify(child, org.mockito.Mockito.never()).sealAgainstLateCallbacks();
        releaseProvider.countDown();
        await(usageSettled);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiUsageSnapshot>> usage =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(root).recordSettledFanOutUsage(any(), any(), usage.capture());
        assertThat(usage.getValue()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.promptTokens()).isEqualTo(9);
            assertThat(snapshot.completionTokens()).isEqualTo(4);
        });
        verify(child, org.mockito.Mockito.atLeastOnce()).sealAgainstLateCallbacks();
    }

    @Test
    void stalledRunSettlesLateUsageFromTheRootChatAttempt() {
        AgentExecutionRecorder root = mock(AgentExecutionRecorder.class);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch usageSettled = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            usageSettled.countDown();
            return null;
        }).when(root).recordSettledFanOutUsage(any(), any(), any());
        AgentExecutionContext rootExecution = context(root, "accepted prompt");
        when(rootExecution.executionPurpose()).thenReturn(
                org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        when(rootExecution.withAgentIdentity(any(), any())).thenReturn(rootExecution);
        when(rootExecution.withToolBinding(any())).thenReturn(rootExecution);
        when(rootExecution.finalizeInstruction(any())).thenAnswer(
                invocation -> invocation.getArgument(0));
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong();
        WorkflowRunBudget budget = new WorkflowRunBudget("request", root,
                Duration.ofSeconds(1), () -> { }, clock::get);
        var execution = TestAgentExecutionService.chat(session -> {
            clock.set(TimeUnit.SECONDS.toNanos(2));
            boolean released = false;
            while (!released) {
                try {
                    released = releaseProvider.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // The provider has admitted the request and cannot cancel it.
                }
            }
            return new org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult(
                    "late root chat", Map.of(),
                    java.util.Optional.of(new AgentRunResult.Usage(11, 5, 1)));
        });
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("root-chat-agent"), "Root Chat Agent",
                "Publishes root usage after cancellation.",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Chat(
                        rootExecution, new Agent.Instruction("instruction")),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), false));
        AgentWorkflowContext context = workflowContext(rootExecution).withRunControl(budget);

        assertThatThrownBy(() -> budget.invoke(
                new AgentRunner(execution, List.of(agent)), agent.callId(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
        budget.settleUsage();

        verify(root, org.mockito.Mockito.never())
                .recordSettledFanOutUsage(any(), any(), any());
        releaseProvider.countDown();
        await(usageSettled);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiUsageSnapshot>> usage =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(root).recordSettledFanOutUsage(any(), any(), usage.capture());
        assertThat(usage.getValue()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.promptTokens()).isEqualTo(11);
            assertThat(snapshot.completionTokens()).isEqualTo(5);
        });
        verify(root, org.mockito.Mockito.atLeastOnce()).sealUsageAccounting();
    }

    @Test
    void sequentialRootChatAgentsSettleRecorderDeltasWithoutDoubleCounting() {
        AgentExecutionRecorder root = mock(AgentExecutionRecorder.class);
        AtomicInteger completedCalls = new AtomicInteger();
        when(root.usageSnapshot()).thenAnswer(ignored -> {
            long calls = completedCalls.get();
            return new AiUsageSnapshot("root", "Root", calls * 5L, calls * 2L, calls);
        });
        AgentExecutionContext rootExecution = context(root, "accepted prompt");
        when(rootExecution.executionPurpose()).thenReturn(
                org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        when(rootExecution.withAgentIdentity(any(), any())).thenReturn(rootExecution);
        when(rootExecution.withToolBinding(any())).thenReturn(rootExecution);
        when(rootExecution.finalizeInstruction(any())).thenAnswer(
                invocation -> invocation.getArgument(0));
        var execution = TestAgentExecutionService.chat(session -> {
            completedCalls.incrementAndGet();
            return new org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult(
                    "answer", Map.of(),
                    java.util.Optional.of(new AgentRunResult.Usage(5, 2, 1)));
        });
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("root-chat-agent"), "Root Chat Agent", "Runs twice.",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Chat(
                        rootExecution, new Agent.Instruction("instruction")),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), false));
        WorkflowRunBudget budget = new WorkflowRunBudget("request", root,
                Duration.ofSeconds(2), () -> { });
        AgentWorkflowContext context = workflowContext(rootExecution).withRunControl(budget);
        AgentRunner runner = new AgentRunner(execution, List.of(agent));

        budget.invoke(runner, agent.callId(), context);
        budget.invoke(runner, agent.callId(), context);
        budget.settleUsage();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiUsageSnapshot>> usage =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(root).recordSettledFanOutUsage(any(), any(), usage.capture());
        assertThat(usage.getValue()).hasSize(2)
                .allSatisfy(snapshot -> {
                    assertThat(snapshot.promptTokens()).isEqualTo(5L);
                    assertThat(snapshot.completionTokens()).isEqualTo(2L);
                    assertThat(snapshot.modelCalls()).isEqualTo(1L);
                });
    }

    @Test
    void nonReturningAttemptIsFencedAndSettledAfterTheBoundedGraceWindow()
            throws InterruptedException {
        AgentExecutionRecorder root = mock(AgentExecutionRecorder.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch accountingClosed = new CountDownLatch(1);
        CountDownLatch usageSettled = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            usageSettled.countDown();
            return null;
        }).when(root).recordSettledFanOutUsage(any(), any(), any());
        WorkflowRunBudget budget = new WorkflowRunBudget("request", root,
                Duration.ofMillis(20), () -> { });
        budget.registerAttemptUsage(
                () -> new AiUsageSnapshot("blocked", "Blocked", 2, 1, 1),
                accountingClosed::countDown);
        Agent blocked = agent("blocked", new ArrayList<>(), ignored -> {
            boolean finished = false;
            while (!finished) {
                try {
                    finished = release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignoredInterrupt) {
                    // Simulate a provider that does not honor cancellation.
                }
            }
            return complete("released");
        });

        assertThatThrownBy(() -> budget.invoke(
                new AgentRunner(null, List.of(blocked)), blocked.callId(),
                workflowContext(context()).withRunControl(budget)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity lease");
        budget.settleUsage();

        assertThat(accountingClosed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(usageSettled.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void controlPlaneAgentCannotBeSelectedAsAPlanWorker() {
        Agent gateway = agent("gateway-agent", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(plan("invalid", "workflow-evaluator")));
        Agent evaluator = agent("workflow-evaluator", new ArrayList<>(),
                ignored -> complete("must-not-run"));

        assertThatThrownBy(() -> workflow(gateway, evaluator).execute(workflowContext(context())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not assignable");
    }

    @Test
    void engineReducesToolAuthorityForEveryAssignableAgent() {
        AgentExecutionContext root = context();
        Agent gateway = agent("gateway-agent", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(plan("restricted", "worker")));
        Agent worker = agent("worker", new ArrayList<>(), context -> {
            assertThat(context.execution().toolPolicy())
                    .isEqualTo(org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy.NONE);
            return complete("safe");
        });

        assertThat(workflow(gateway, worker).execute(workflowContext(root)).content())
                .isEqualTo("safe");
    }

    @Test
    void failedReplanRetainsTheLastSuccessfulCandidate() {
        AtomicInteger planCalls = new AtomicInteger();
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("workflow-planner"));
        Agent planner = agent("workflow-planner", new ArrayList<>(), ignored ->
                new AgentDecision.Delegate(planCalls.incrementAndGet() == 1
                        ? plan("first-attempt", "successful-worker")
                        : plan("second-attempt", "failed-worker")));
        Agent successful = agent("successful-worker", new ArrayList<>(),
                ignored -> complete("last good answer"));
        Agent failed = agent("failed-worker", new ArrayList<>(), ignored -> {
            throw new IllegalStateException("retry failed");
        });
        Agent evaluator = agent("workflow-evaluator", new ArrayList<>(), context ->
                new AgentDecision.Handoff(new Agent.AgentId("workflow-planner"),
                        new AiWorkflowFeedback(1, "first-attempt",
                                context.candidate().content(), "try again", "improve")));

        AgentOutput result = workflow(gateway, planner, successful, failed, evaluator)
                .execute(workflowContext(context()));

        assertThat(result.content()).isEqualTo("last good answer");
        assertThat(result.metadata())
                .containsEntry("evaluation_status", "iteration_failed")
                .containsEntry("failed_iteration", 2);
    }

    @Test
    void acceptedTurnIsTheOnlyPromptVisibleToDownstreamAgents() {
        AgentExecutionContext execution = context(
                mock(AgentExecutionRecorder.class), "rewritten safe input");
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> handoff("workflow-planner"));
        Agent planner = agent("workflow-planner", new ArrayList<>(), context -> {
            assertThat(context.request().prompt()).isEqualTo("rewritten safe input");
            return new AgentDecision.Delegate(plan("accepted", "worker"));
        });
        Agent worker = agent("worker", new ArrayList<>(),
                ignored -> complete("done"));

        assertThat(workflow(gateway, planner, worker).execute(workflowContext(execution)).content())
                .isEqualTo("done");
    }

    @Test
    void mainWorkflowKeepsTheRootRecorderOpenAndSettlesUsageOnce() {
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        List<AiExecutionEvent> events = new ArrayList<>();
        AiTrajectoryRecorder root = new AiTrajectoryRecorder(repository, new ObjectMapper(),
                null, "conversation-1", "request-1", "model", "high", events::add);
        AgentExecutionContext execution = context(root, "accepted prompt");
        Agent gateway = agent("gateway-agent", new ArrayList<>(), context -> {
            context.recordUsage(new AiUsageSnapshot(
                    "main:gateway", "Gateway", 11, 7, 1));
            return complete("answer");
        });

        workflow(gateway).execute(workflowContext(execution));
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
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan));
        Agent successful = agent("successful-worker", new ArrayList<>(),
                ignored -> complete("mutation committed"));
        Agent failed = agent("failed-worker", new ArrayList<>(), ignored -> {
            throw new IllegalStateException("follow-up failed");
        });
        Agent synthesizer = agent("workflow-synthesizer", new ArrayList<>(), context -> {
            assertThat(context.inputs()).extracting(WorkflowResult::successful)
                    .containsExactly(true, false);
            return complete("mutation committed");
        });

        AgentOutput result = workflow(
                gateway, successful, failed, synthesizer).execute(workflowContext(context()));

        assertThat(result.content()).contains("mutation committed",
                "Some requested steps could not be completed",
                "may already have taken effect");
        assertThat(result.metadata())
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
        Agent gateway = agent("gateway-agent", new ArrayList<>(),
                ignored -> new AgentDecision.Delegate(plan));
        Agent successful = agent("successful-worker", new ArrayList<>(),
                ignored -> complete("completed"));
        Agent failed = agent("failed-worker", new ArrayList<>(), ignored -> {
            throw new IllegalStateException("nested failure");
        });
        Agent synthesizer = agent("workflow-synthesizer", new ArrayList<>(),
                context -> complete(context.workflow().root().id() + " synthesized"));

        AgentOutput result = workflow(
                gateway, successful, failed, synthesizer).execute(workflowContext(context()));

        assertThat(result.content()).contains("outer synthesized",
                "Some requested steps could not be completed",
                "may already have taken effect");
        assertThat(result.metadata())
                .containsEntry("completed", 2)
                .containsEntry("direct_failed", 0)
                .containsEntry("failed", 1)
                .containsEntry("partial_failure", true)
                .containsEntry("partial_failure_count", 1)
                .containsEntry("partial_failure_notice", true);
    }

    private WorkflowRunner workflow(Agent... agents) {
        return new WorkflowRunner(new AgentRunner(null, List.of(agents)), null, 3);
    }

    private Agent agent(String id, List<String> calls,
                        Function<AgentWorkflowContext, AgentDecision> operation) {
        boolean assignable = !java.util.Set.of("gateway-agent", "connectcenter-assistant",
                "workflow-planner", "workflow-evaluator",
                "workflow-synthesizer").contains(id);
        AgentDefinition definition = new AgentDefinition(
                new Agent.AgentId(id), id, id + " description",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> {
                    calls.add(id);
                    return new AgentRunRequest.Skip(operation.apply(context));
                },
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), assignable);
        return new DefinedAgent(definition);
    }

    private AgentDecision.Complete complete(String answer) {
        return new AgentDecision.Complete(new AgentOutput(answer, Map.of()));
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

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for parallel Workflow members.",
                    interrupted);
        }
    }

    private AgentWorkflowContext workflowContext(AgentExecutionContext execution) {
        return AgentWorkflowContext.root(execution,
                new AgentWorkflowContext.Request("request-1", "conversation-1", "user-1",
                        "model", execution.userMessage().content(), false, false, 4, "balanced",
                        null, true, false), 3);
    }

    private AgentExecutionContext context() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        when(recorder.fork(any())).thenReturn(recorder);
        return context(recorder, "accepted prompt");
    }

    private AgentExecutionContext context(AiTrajectoryRecorder recorder, String prompt) {
        return context(AgentExecutionRecorderAdapter.of(recorder), prompt);
    }

    private AgentExecutionContext context(AgentExecutionRecorder recorder, String prompt) {
        if (org.mockito.Mockito.mockingDetails(recorder).isMock()) {
            when(recorder.fork(any())).thenReturn(recorder);
        }
        AgentExecutionContext execution = mock(AgentExecutionContext.class);
        when(execution.requestId()).thenReturn("request-1");
        when(execution.conversationId()).thenReturn("conversation-1");
        when(execution.modelName()).thenReturn("model");
        when(execution.requesterId()).thenReturn("user-1");
        when(execution.userMessage()).thenReturn(new AiMessage.User(prompt));
        when(execution.history()).thenReturn(List.of());
        when(execution.recorder()).thenReturn(recorder);
        when(execution.toolPolicy()).thenReturn(AgentToolPolicy.NONE);
        when(execution.forWorkflowAssignment(any(), any())).thenReturn(execution);
        return execution;
    }
}
