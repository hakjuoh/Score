package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Request-scoped executable Agent created from a catalog definition and a Planner assignment. */
public final class AssignedAgent implements WorkflowAgent {

    private static final int MAX_UPSTREAM_EVIDENCE_LENGTH = 24_000;
    private final AgentDefinition definition;
    private final AiChatExecutor executor;
    private final AgentInstructions instructions;

    public AssignedAgent(AgentDefinition definition, AiChatExecutor executor,
                         AgentInstructions instructions) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.instructions = Objects.requireNonNull(instructions, "instructions");
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    @Override
    public boolean assignable() {
        return true;
    }

    @Override
    public AgentDecision execute(AgentWorkflowContext context) {
        AiWorkflowPlan.AgentTask task = Objects.requireNonNull(
                context.assignment(), "Agent assignment");
        AiChatExecutor.Context parent = context.execution();
        AiChatExecutor.ToolPolicy policy = toolPolicy(parent, task.toolAccess());
        Map<String, Object> namespace = namespace(context, task);
        AiTrajectoryRecorder recorder = Objects.requireNonNull(
                parent.recorder().forkSubagent(id().value(), task.instruction(), namespace),
                "The assigned Agent recorder is required.");
        context.registerUsage(recorder::usageSnapshot,
                recorder::sealAgainstLateCallbacks);
        String childConversationId = recorder.conversationId();
        recorder.lifecycle("subagent_started", status(task, false),
                lifecycle(namespace, "started"));
        try {
            List<Message> history = workerHistory(parent, context.inputs(), task, policy);
            UserMessage assignment = UserMessage.builder()
                    .text(instructions.render(AgentInstructions.Template.WORKER_ASSIGNMENT,
                            Map.of("assignment", task.instruction())).value())
                    .media(parent.userMessage().getMedia()).build();
            AiMutationApprovalScope approvalScope = AiMutationApprovalScope.individual(
                    rootConversationId(parent), childConversationId,
                    id().value(), task.label());
            AiChatExecutor.Context child = new AiChatExecutor.Context(
                    parent.request().withConversationId(childConversationId)
                            .withMultiAgent(AiMultiAgentOptions.single()),
                    history, assignment, parent.requester(), recorder,
                    policy != AiChatExecutor.ToolPolicy.NONE, false, policy, 1,
                    approvalScope)
                    .withAgentIdentity(id().value(), ExecutionScope.Purpose.WORKER)
                    .withGuardrailDecisions(parent.guardrailDecisionIds())
                    .withWorkflowObservationContext(context.observationContext());
            AiChatExecutor.Result result = executor.execute(child);
            Map<String, Object> metadata = new LinkedHashMap<>(result.traceMetadata());
            metadata.putAll(lifecycle(namespace, "completed"));
            recorder.terminalLifecycle("subagent_completed", status(task, true), metadata);
            return new AgentDecision.Complete(
                    new AiChatExecutor.Result(result.answer(), Map.copyOf(metadata)));
        } catch (CancellationException failure) {
            recorder.terminalLifecycle("subagent_cancelled",
                    "Stopped " + task.label() + ".",
                    lifecycle(namespace, "cancelled"));
            throw failure;
        } catch (AgentInputRefusedException refusal) {
            recorder.terminalLifecycle("subagent_refused",
                    "Policy refused " + task.label() + ".",
                    lifecycle(namespace, "refused"));
            throw refusal;
        } catch (RuntimeException failure) {
            recorder.terminalLifecycle("subagent_failed",
                    "Could not complete " + task.label() + ".",
                    lifecycle(namespace, "failed", Map.of(
                            "reason", failure.getClass().getSimpleName())));
            throw failure;
        }
    }

    private List<Message> workerHistory(AiChatExecutor.Context parent,
                                        List<org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult> upstream,
                                        AiWorkflowPlan.AgentTask task,
                                        AiChatExecutor.ToolPolicy policy) {
        AgentInstructions.Template template = policy == AiChatExecutor.ToolPolicy.FULL
                ? AgentInstructions.Template.WORKER_FULL
                : AgentInstructions.Template.WORKER_RESTRICTED;
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(instructions.render(template, Map.of(
                "agentInstruction", definition.instruction().value())).value()));
        history.add(new UserMessage(instructions.render(
                AgentInstructions.Template.ORIGINAL_REQUEST_REFERENCE,
                Map.of("originalRequest", Objects.requireNonNullElse(
                        parent.userMessage().getText(), ""))).value()));
        if (!upstream.isEmpty()) {
            StringBuilder references = new StringBuilder();
            upstream.stream().filter(org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult::successful)
                    .forEach(result -> references.append("- ").append(result.workflowId())
                            .append(": ").append(result.output()).append('\n'));
            if (!references.isEmpty()) {
                String evidence = references.toString().strip();
                if (evidence.length() > MAX_UPSTREAM_EVIDENCE_LENGTH) {
                    evidence = evidence.substring(0, MAX_UPSTREAM_EVIDENCE_LENGTH)
                            .stripTrailing() + "\n[UPSTREAM EVIDENCE TRUNCATED]";
                }
                history.add(new UserMessage(instructions.render(
                        AgentInstructions.Template.UPSTREAM_RESULTS,
                        Map.of("results", evidence)).value()));
            }
        }
        return List.copyOf(history);
    }

    private AiChatExecutor.ToolPolicy toolPolicy(AiChatExecutor.Context parent,
                                                 AiWorkflowPlan.ToolAccess access) {
        if (!parent.toolsEnabled() || parent.toolPolicy() == AiChatExecutor.ToolPolicy.NONE
                || access == AiWorkflowPlan.ToolAccess.NONE) {
            return AiChatExecutor.ToolPolicy.NONE;
        }
        if (access == AiWorkflowPlan.ToolAccess.FULL
                && parent.toolPolicy() == AiChatExecutor.ToolPolicy.FULL) {
            return AiChatExecutor.ToolPolicy.FULL;
        }
        return AiChatExecutor.ToolPolicy.READ_ONLY;
    }

    private String rootConversationId(AiChatExecutor.Context context) {
        return context.approvalScope() != null
                ? context.approvalScope().rootConversationId()
                : context.request().conversationId();
    }

    private Map<String, Object> namespace(AgentWorkflowContext context,
                                          AiWorkflowPlan.AgentTask task) {
        AgentWorkflowContext.Location location = Objects.requireNonNull(
                context.location(), "Workflow location");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflow", location.workflowId());
        value.put("node_id", location.nodeId() + ":agent:" + context.assignmentId());
        value.put("parent_node_id", location.nodeId());
        value.put("agent_id", id().value());
        value.put("agent_name", definition.name());
        value.put("task_label", task.label());
        value.put("depth", location.depth() + 1);
        return Map.copyOf(value);
    }

    private Map<String, Object> lifecycle(Map<String, Object> namespace, String status) {
        return lifecycle(namespace, status, Map.of());
    }

    private Map<String, Object> lifecycle(Map<String, Object> namespace, String status,
                                          Map<String, Object> additional) {
        Map<String, Object> value = new LinkedHashMap<>(namespace);
        value.put("status", status);
        value.putAll(additional);
        return Map.copyOf(value);
    }

    private String status(AiWorkflowPlan.AgentTask task, boolean completed) {
        String value = completed ? task.completedVerb() : task.guideMessage();
        if (!StringUtils.hasText(value)) value = completed ? "Completed" : task.activeVerb();
        if (!StringUtils.hasText(value)) value = completed ? "Completed" : "Working";
        String normalized = value.strip();
        return normalized.matches(".*[.!?。！？]$") ? normalized : normalized + ".";
    }
}
