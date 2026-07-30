package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring AI adapter for a Planner assignment.
 *
 * <p>The public {@code AssignedAgent} remains a definition value. This adapter
 * supplies the transport-specific request and lifecycle handlers to that
 * definition, while the shared {@code AgentRunner} still owns execution.</p>
 */
public final class AssignedAgentHandlers {

    private static final int MAX_UPSTREAM_EVIDENCE_LENGTH = 24_000;

    private final AgentInstructions instructions;

    public AssignedAgentHandlers(AgentInstructions instructions) {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
    }

    public AgentRequestHandler requestHandler() {
        return this::prepare;
    }

    public AgentResponseHandler responseHandler() {
        return this::respond;
    }

    private AgentRunRequest prepare(Agent agent, AgentWorkflowContext context) {
        AiWorkflowPlan.AgentTask task = Objects.requireNonNull(
                context.assignment(), "Agent assignment");
        if (task.delegation() == AiWorkflowPlan.Delegation.FAN_OUT) {
            return new AgentRunRequest.Skip(
                    new AgentDecision.Handoff(AssistantAgent.PLANNER_ID));
        }
        AgentExecutionContext parent = context.execution();
        AgentToolPolicy policy = toolPolicy(parent, task.toolAccess());
        Map<String, Object> namespace = AgentAssignmentLifecycle.namespace(agent, context, task);
        AgentExecutionRecorder recorder = Objects.requireNonNull(
                parent.recorder().forkSubagent(agent.id().value(), task.instruction(), namespace),
                "The assigned Agent recorder is required.");
        String childConversationId = recorder.conversationId();
        AgentInstructions.Template template = policy == AgentToolPolicy.FULL
                ? AgentInstructions.Template.WORKER_FULL
                : AgentInstructions.Template.WORKER_RESTRICTED;
        Agent.Instruction workerInstruction = instructions.render(template, Map.of(
                "agentInstruction", agent.definition().instruction().value()));
        List<AiMessage> history = workerHistory(parent, context.inputs(), task);
        AiMessage.User assignment = new AiMessage.User(
                instructions.render(AgentInstructions.Template.WORKER_ASSIGNMENT,
                        Map.of("assignment", task.instruction())).value(),
                parent.userMessage().attachments());
        AiChangeApprovalScope approvalScope = AiChangeApprovalScope.individual(
                rootConversationId(parent), childConversationId, agent.id().value(), task.label());
        AgentExecutionContext child = parent.forAssignedAgent(childConversationId,
                history, assignment, recorder, policy, agent.id().value(), approvalScope)
                .withAgentIdentity(agent.id().value(),
                        org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER)
                .withGuardrailDecisions(parent.guardrailDecisionIds())
                .withWorkflowObservationContext(context.observationContext());
        return new AgentRunRequest.Chat(child, workerInstruction);
    }

    private AgentDecision respond(AgentResponseContext response) {
        AgentWorkflowContext context = response.workflow();
        AiWorkflowPlan.AgentTask task = Objects.requireNonNull(
                context.assignment(), "Agent assignment");
        Map<String, Object> metadata = new LinkedHashMap<>(
                response.result().metadata().attributes());
        metadata.putAll(AgentAssignmentLifecycle.metadata(
                AgentAssignmentLifecycle.namespace(response.agent(), context, task), "completed"));
        return new AgentDecision.Complete(new AgentOutput(
                response.result().response().content(), Map.copyOf(metadata)));
    }

    private List<AiMessage> workerHistory(AgentExecutionContext parent,
                                        List<WorkflowResult> upstream,
                                        AiWorkflowPlan.AgentTask task) {
        List<AiMessage> history = new ArrayList<>();
        history.add(new AiMessage.User(instructions.render(
                AgentInstructions.Template.ORIGINAL_REQUEST_REFERENCE,
                Map.of("originalRequest", Objects.requireNonNullElse(
                        parent.userMessage().content(), ""))).value()));
        if (!upstream.isEmpty()) {
            StringBuilder references = new StringBuilder();
            upstream.stream().filter(WorkflowResult::successful)
                    .forEach(result -> references.append("- ").append(result.workflowId())
                            .append(": ").append(result.output()).append('\n'));
            if (!references.isEmpty()) {
                String evidence = references.toString().strip();
                if (evidence.length() > MAX_UPSTREAM_EVIDENCE_LENGTH) {
                    evidence = evidence.substring(0, MAX_UPSTREAM_EVIDENCE_LENGTH)
                            .stripTrailing() + "\n[UPSTREAM EVIDENCE TRUNCATED]";
                }
                history.add(new AiMessage.User(instructions.render(
                        AgentInstructions.Template.UPSTREAM_RESULTS,
                        Map.of("results", evidence)).value()));
            }
        }
        return List.copyOf(history);
    }

    private AgentToolPolicy toolPolicy(AgentExecutionContext parent,
                                                  AiWorkflowPlan.ToolAccess access) {
        return AgentToolPolicy.restrict(parent.toolPolicy(), parent.toolsEnabled(),
                access == AiWorkflowPlan.ToolAccess.FULL,
                access == AiWorkflowPlan.ToolAccess.NONE);
    }

    private String rootConversationId(AgentExecutionContext context) {
        return context.approvalScope() != null
                ? context.approvalScope().rootConversationId()
                : context.conversationId();
    }

}
