package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Executes each ready layer of a Workflow DAG concurrently and joins it deterministically. */
final class WorkflowGraphScheduler {

    List<WorkflowResult> execute(AiWorkflowPlan.WorkflowDefinition workflow,
                                 List<WorkflowResult> inherited,
                                 Runnable checkpoint,
                                 MemberExecution execution) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(execution, "execution");
        List<WorkflowResult> inheritedInputs = inherited != null
                ? inherited.stream().filter(WorkflowResult::successful).toList() : List.of();
        Set<String> pending = new LinkedHashSet<>();
        workflow.members().forEach(member -> pending.add(member.id()));
        Map<String, WorkflowResult> completed = new LinkedHashMap<>();

        while (!pending.isEmpty()) {
            checkpoint.run();
            List<AiWorkflowPlan.Member> ready = workflow.readyMembers(
                    pending, completed.keySet());
            if (ready.isEmpty()) {
                throw new IllegalStateException(
                        "Workflow has no ready member; its dependency graph is invalid.");
            }

            List<RunningMember> running = new ArrayList<>(ready.size());
            try {
                for (AiWorkflowPlan.Member member : ready) {
                    List<WorkflowResult> upstream = upstream(
                            workflow, member, inheritedInputs, completed);
                    FutureTask<WorkflowResult> task = new FutureTask<>(
                            () -> execution.execute(member, upstream));
                    Thread worker = Thread.startVirtualThread(task);
                    running.add(new RunningMember(member.id(), task, worker));
                }
            } catch (RuntimeException | Error startFailure) {
                cancel(running);
                throw startFailure;
            }
            try {
                Map<String, WorkflowResult> layer = awaitLayer(
                        running, checkpoint);
                // Publish in declaration order even though completion order is arbitrary.
                for (RunningMember member : running) {
                    completed.put(member.id(), layer.get(member.id()));
                    pending.remove(member.id());
                }
            } catch (RuntimeException | Error failure) {
                cancel(running);
                throw failure;
            }
        }
        return workflow.members().stream().map(member -> completed.get(member.id())).toList();
    }

    private Map<String, WorkflowResult> awaitLayer(List<RunningMember> running,
                                                    Runnable checkpoint) {
        Map<String, WorkflowResult> completed = new LinkedHashMap<>();
        while (completed.size() < running.size()) {
            checkpoint.run();
            boolean progressed = false;
            for (RunningMember member : running) {
                if (completed.containsKey(member.id()) || !member.task().isDone()) continue;
                try {
                    completed.put(member.id(), member.task().get());
                    progressed = true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException(
                            "Workflow graph execution was interrupted.");
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException(
                            "Workflow member execution failed.", cause);
                }
            }
            if (!progressed) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(
                        "Workflow graph execution was interrupted.");
            }
        }
        return completed;
    }

    private List<WorkflowResult> upstream(AiWorkflowPlan.WorkflowDefinition workflow,
                                          AiWorkflowPlan.Member member,
                                          List<WorkflowResult> inherited,
                                          Map<String, WorkflowResult> completed) {
        List<WorkflowResult> inputs = new ArrayList<>(inherited);
        workflow.predecessors(member.id()).stream()
                .map(completed::get)
                .filter(Objects::nonNull)
                .filter(WorkflowResult::successful)
                .forEach(inputs::add);
        return List.copyOf(inputs);
    }

    private void cancel(List<RunningMember> running) {
        running.forEach(member -> {
            if (!member.task().isDone()) {
                member.task().cancel(true);
                member.worker().interrupt();
            }
        });
    }

    @FunctionalInterface
    interface MemberExecution {
        WorkflowResult execute(AiWorkflowPlan.Member member,
                               List<WorkflowResult> upstream);
    }

    private record RunningMember(String id, FutureTask<WorkflowResult> task, Thread worker) {
    }
}
