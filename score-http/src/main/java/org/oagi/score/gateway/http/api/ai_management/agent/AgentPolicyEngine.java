package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies one Agent definition's policy without owning execution.
 *
 * <p>This class lives beside {@link AgentOutput} so only a real policy evaluation
 * can issue the output's opaque, non-metadata disclosure evidence.</p>
 */
public final class AgentPolicyEngine {

    private final boolean chatExecutionConfigured;

    public AgentPolicyEngine(boolean chatExecutionConfigured) {
        this.chatExecutionConfigured = chatExecutionConfigured;
    }

    /** Applies output policy to a non-retryable local or failure-handler decision. */
    public AgentDecision applyTerminalDecision(Agent agent, AgentWorkflowContext context,
                                               AgentDecision decision) {
        DecisionCheck checked = applyOutput(agent, context, decision);
        if (checked.retryFeedback() != null) {
            throw new AgentOutputRetryLimitException(agent.id(), checked.retryFeedback());
        }
        return checked.decision();
    }

    public AgentRunRequest applyInput(Agent agent, AgentWorkflowContext context,
                                      AgentRunRequest request) {
        AgentGuardrails guardrails = agent.guardrails();
        if (!guardrails.hasInput()) return request;

        if (request instanceof AgentRunRequest.Model model) {
            List<AiMessage> assembled = new ArrayList<>();
            assembled.add(new AiMessage.System(model.instruction().value()));
            assembled.addAll(model.history());
            assembled.add(model.input());
            AgentInputGuardrailChain.Outcome outcome = inputOutcome(agent, model.input(),
                    assembled, model.scope());
            if (outcome.input() == model.input()) return request;
            return new AgentRunRequest.Model(model.modelName(), model.instruction(), outcome.input(),
                    model.history(), model.scope(), model.observationContext());
        }

        if (!chatExecutionConfigured) {
            throw new IllegalStateException("No chat execution service is configured.");
        }
        AgentRunRequest.Chat chat = (AgentRunRequest.Chat) request;
        AiMessage.User input = chat.context().userMessage();
        List<AiMessage> assembled = new ArrayList<>(chat.context().history());
        assembled.add(input);
        AgentInputGuardrailChain.Outcome outcome = inputOutcome(agent, input, assembled,
                context.executionScope(ExecutionScope.Purpose.GUARDRAIL_EVALUATION));
        if (outcome.input() == input) return request;
        return new AgentRunRequest.Chat(
                chat.context().withUserMessage(outcome.input()), chat.instruction());
    }

    /** Applies input policy before a definition can short-circuit into a local result. */
    public AgentWorkflowContext prepareInputContext(Agent agent, AgentWorkflowContext context) {
        if (!agent.guardrails().hasInput()) return context;
        AgentExecutionContext execution = context.execution();
        AiMessage.User input = Objects.requireNonNull(execution.userMessage(),
                "Agent input guardrails require a user message");
        List<AiMessage> assembled = new ArrayList<>(execution.history());
        assembled.add(input);
        AgentInputGuardrailChain.Outcome outcome = inputOutcome(agent, input, assembled,
                context.executionScope(ExecutionScope.Purpose.GUARDRAIL_EVALUATION));
        return outcome.input() == input
                ? context : context.withExecution(execution.withUserMessage(outcome.input()));
    }

    /** Evaluates the final content returned by the response handler. */
    public DecisionCheck applyOutput(Agent agent, AgentWorkflowContext context,
                                     AgentDecision decision) {
        if (!(decision instanceof AgentDecision.Complete complete)
                || !agent.guardrails().hasOutput()) {
            return new DecisionCheck(decision, null);
        }
        AgentOutput candidate = complete.result();
        AgentOutputGuardrailChain.Outcome outcome = outputOutcome(agent, context,
                new AiMessage.Assistant(candidate.content()));
        if (!outcome.allowed()) {
            if (outcome.refusal() != null) {
                throw new AgentGuardrailRefusedException(outcome.refusal(), agent.id());
            }
            return new DecisionCheck(decision, outcome.retryFeedback());
        }
        Map<String, Object> metadata = acceptedMetadata(candidate.metadata(), outcome,
                agent.guardrails().outputScope());
        String content = outcome.output() != null
                ? outcome.output().content() : candidate.content();
        AgentOutput accepted = AgentOutput.policyChecked(
                content, metadata, agent.guardrails().outputScope());
        return new DecisionCheck(new AgentDecision.Complete(accepted), null);
    }

    public AgentRunRequest applyRetryFeedback(AgentRunRequest request, String feedback) {
        if (request instanceof AgentRunRequest.Model model) {
            AiMessage.User input = model.input();
            return new AgentRunRequest.Model(model.modelName(), model.instruction(),
                    new AiMessage.User(input.content() + "\n\nOUTPUT_POLICY_FEEDBACK\n" + feedback,
                            input.attachments()), model.history(), model.scope(),
                    model.observationContext());
        }
        if (request instanceof AgentRunRequest.Chat chat) {
            return new AgentRunRequest.Chat(
                    chat.context().withRetryFeedback(feedback), chat.instruction());
        }
        return request;
    }

    private AgentInputGuardrailChain.Outcome inputOutcome(
            Agent agent, AiMessage.User input, List<AiMessage> assembled, ExecutionScope scope) {
        AgentInputGuardrailChain.Outcome outcome = new AgentInputGuardrailChain(
                agent.guardrails().input()).evaluate(new AgentInputGuardrail.Request(
                AgentInputGuardrail.Scope.TURN_LOCAL, input, assembled, scope,
                Map.of("agent_id", agent.id().value())));
        if (!outcome.allowed()) {
            throw new AgentGuardrailRefusedException(outcome.refusal(), agent.id());
        }
        return outcome;
    }

    private AgentOutputGuardrailChain.Outcome outputOutcome(
            Agent agent, AgentWorkflowContext context, AiMessage.Assistant candidate) {
        return new AgentOutputGuardrailChain(agent.guardrails().output()).evaluate(
                new AgentOutputGuardrail.Request(agent.guardrails().outputScope(),
                        candidate, context.executionScope(
                        ExecutionScope.Purpose.GUARDRAIL_EVALUATION),
                        Map.of("agent_id", agent.id().value())));
    }

    private Map<String, Object> acceptedMetadata(
            Map<String, Object> source, AgentOutputGuardrailChain.Outcome outcome,
            AgentOutputGuardrail.Scope scope) {
        Map<String, Object> metadata = new LinkedHashMap<>(source);
        metadata.put(AgentOutput.OUTPUT_GUARDRAIL_APPLIED, true);
        metadata.put(AgentOutput.OUTPUT_GUARDRAIL_SCOPE, scope.name());
        if (!outcome.decisions().isEmpty()) {
            metadata.put("output_guardrail_decisions", outcome.decisions().stream()
                    .map(GuardrailDecision::decisionId).toList());
        }
        return Map.copyOf(metadata);
    }

    public record DecisionCheck(AgentDecision decision, String retryFeedback) {
        public DecisionCheck {
            Objects.requireNonNull(decision, "decision");
        }
    }
}
