package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.ResponseOnlyAgent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Final public-output policy boundary, including the single bounded regeneration attempt. */
final class ChatOutputDiscloser {

    private final AgentOutputGuardrailChain outputGuardrails;
    private final ResponseOnlyAgent responseOnlyAgent;
    private final ScoreAiObservability observability;
    private final PublicOutputDisclosureGate disclosureGate;
    private final StandaloneAgentExecutor standaloneAgents;

    ChatOutputDiscloser(AgentOutputGuardrailChain outputGuardrails,
                        ResponseOnlyAgent responseOnlyAgent,
                        ScoreAiObservability observability,
                        StandaloneAgentExecutor standaloneAgents) {
        this.outputGuardrails = outputGuardrails;
        this.responseOnlyAgent = responseOnlyAgent;
        this.observability = observability;
        this.disclosureGate = new PublicOutputDisclosureGate(outputGuardrails, observability);
        this.standaloneAgents = standaloneAgents;
    }

    ChatDisclosure disclose(ChatTurnOutput output, ChatRequest request,
                            List<Message> history, UserMessage userMessage,
                            ScoreUser requester, AiTrajectoryRecorder recorder,
                            ExecutionScope scope) {
        PublicOutputDisclosureGate.Outcome guarded = output.retryFeedback() != null
                ? new PublicOutputDisclosureGate.Outcome(
                null, output.retryFeedback(), null, List.of())
                : output.policyRefusal()
                ? PublicOutputDisclosureGate.Outcome.allowed(output.answer())
                : disclosureGate.evaluate(output.disclosureCandidate(), scope,
                Map.of("gateway", Boolean.TRUE.equals(output.metadata().get("gateway")),
                        "model", request.modelName()));
        Map<String, Object> metadata = output.metadata();
        if (guarded.retryRequested()) {
            try {
                AgentOutput regenerated = regenerate(request, history, userMessage,
                        output.answer(), guarded.retryFeedback(), requester, recorder, scope);
                Map<String, Object> merged = new LinkedHashMap<>(metadata);
                merged.putAll(regenerated.metadata());
                metadata = Map.copyOf(merged);
                guarded = disclosureGate.evaluate(regenerated, scope,
                        Map.of("agent_id", responseOnlyAgent.definition().id().value(),
                                "response_only_retry", true));
            } catch (AgentGuardrailRefusedException refused) {
                guarded = new PublicOutputDisclosureGate.Outcome(null, null,
                        refused.refusal(), List.of(refused.refusal().decision()));
            }
        }
        String answer = guarded.output() != null ? guarded.output()
                : publicGuardrailMessage(guarded.refusal() != null
                ? guarded.refusal() : outputRetryUnavailable());
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException(
                    "The Agent output policy returned an empty response.");
        }
        return new ChatDisclosure(answer, metadata, guarded);
    }

    Map<String, Object> committedTrace(ChatDisclosure disclosure,
                                       String responseAgentId) {
        Map<String, Object> trace = new LinkedHashMap<>(disclosure.metadata());
        trace.put("agent_id", responseAgentId);
        if (disclosure.outcome().refusal() != null
                || disclosure.outcome().retryRequested()) {
            trace.put("finish_reason", "GUARDRAIL_REFUSAL");
        }
        if (!disclosure.outcome().decisions().isEmpty()) {
            trace.put("output_guardrail_decisions", disclosure.outcome().decisions().stream()
                    .map(GuardrailDecision::decisionId).toList());
        }
        return Map.copyOf(trace);
    }

    String publicGuardrailMessage(GuardrailRefusal refusal) {
        return "ai.policy.refused".equals(refusal.publicMessageKey())
                ? "I can’t help with that request."
                : "I’m unable to process that request safely right now.";
    }

    private AgentOutput regenerate(ChatRequest request, List<Message> history,
                                   UserMessage originalUserMessage, String candidate,
                                   String feedback, ScoreUser requester,
                                   AiTrajectoryRecorder recorder, ExecutionScope turnScope) {
        var responseAgent = Objects.requireNonNull(responseOnlyAgent,
                "The response-only Agent is required for safe output regeneration.").definition();
        List<Message> evidence = new ArrayList<>(history);
        evidence.addFirst(new SystemMessage(responseAgent.instruction().render().value()));
        evidence.add(originalUserMessage);
        evidence.add(new AssistantMessage(candidate));
        UserMessage retry = new UserMessage("""
                Apply this safe policy feedback to the response: %s
                """.formatted(Objects.requireNonNullElse(
                feedback, "Produce a safe response.")));
        ChatExecutionContext retryContext = ChatExecutionContext.fromRequest(
                        request, evidence, retry, requester, recorder, false, false)
                .withAgentIdentity(responseAgent.id().value(),
                        ExecutionScope.Purpose.RESPONSE_ONLY_RETRY)
                .withGuardrailDecisions(turnScope.guardrailDecisionIds());
        AgentDefinition definition = new AgentDefinition(responseAgent.id(), responseAgent.name(),
                responseAgent.description(), responseAgent.instruction(),
                (agent, context) -> new AgentRunRequest.Chat(retryContext),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailHandlers.publicOutput(
                        outputGuardrails, observability, "response_only_output",
                        Map.of("agent_id", responseAgent.id().value())), false);
        return standaloneAgents.run(new DefinedAgent(definition), retryContext);
    }

    private GuardrailRefusal outputRetryUnavailable() {
        return new GuardrailRefusal(GuardrailDecision.of("bounded-output-retry", "1",
                GuardrailDecision.Action.REFUSE), "SAFE_RETRY_EXHAUSTED",
                "ai.policy.unavailable");
    }
}
