package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral state for one Agent turn.
 *
 * <p>This is the context visible to Agent definitions and Workflows. A
 * transport adapter may carry provider-specific state behind it, but that
 * state must not leak into the Agent or Workflow contracts.</p>
 */
public interface AgentExecutionContext {

    String requestId();

    String conversationId();

    String modelName();

    String requesterId();

    AiMessage.User userMessage();

    List<AiMessage> history();

    AgentExecutionRecorder recorder();

    boolean toolsEnabled();

    AgentToolPolicy toolPolicy();

    /** Definition-owned tools currently bound to this turn, if any. */
    AgentToolBinding toolBinding();

    /** Whether the transport may publish model content while this turn runs. */
    boolean streamVisibleContent();

    int agentDepth();

    /** Provider-independent execution identity of the current Agent. */
    String agentId();

    ExecutionScope.Purpose executionPurpose();

    List<String> guardrailDecisionIds();

    /** Approval scope propagated across nested Workflow Agent turns. */
    AiMutationApprovalScope approvalScope();

    Map<String, Object> workflowObservationContext();

    /** Trusted values used by the shared Runner to render this Agent's instruction. */
    default Map<String, Object> instructionParameters() {
        return Map.of();
    }

    /** Binds trusted application context before the instruction crosses a provider boundary. */
    default Agent.Instruction finalizeInstruction(Agent.Instruction instruction) {
        return java.util.Objects.requireNonNull(instruction, "instruction");
    }

    AgentExecutionContext withUserMessage(AiMessage.User message);

    AgentExecutionContext withRetryFeedback(String feedback);

    AgentExecutionContext withAgentIdentity(String identity,
                                             ExecutionScope.Purpose purpose);

    AgentExecutionContext withGuardrailDecisions(List<String> decisionIds);

    AgentExecutionContext withWorkflowObservationContext(Map<String, Object> value);

    /** Installs a definition-owned Tool binding at the execution boundary. */
    AgentExecutionContext withToolBinding(AgentToolBinding binding);

    AgentExecutionContext forWorkflowAssignment(AgentToolPolicy policy,
                                                String assignedAgentId);

    AgentExecutionContext forAssignedAgent(String childConversationId,
                                           List<AiMessage> history,
                                           AiMessage.User assignment,
                                           AgentExecutionRecorder recorder,
                                           AgentToolPolicy policy,
                                           String assignedAgentId,
                                           AiMutationApprovalScope approvalScope);
}
