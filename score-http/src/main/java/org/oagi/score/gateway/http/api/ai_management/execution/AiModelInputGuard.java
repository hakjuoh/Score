package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies the model-bound input policy to the final assembled Spring AI message list. */
final class AiModelInputGuard {

    private final AgentInputGuardrailChain guardrails;
    private final ScoreAiObservability observability;

    AiModelInputGuard(AgentInputGuardrailChain guardrails, ScoreAiObservability observability) {
        this.guardrails = guardrails;
        this.observability = observability != null ? observability : ScoreAiObservability.noop();
    }

    List<Message> apply(ChatRequest request, List<Message> messages,
                        int guardedUserIndex, ExecutionScope scope) {
        if (guardrails == null) return messages;
        List<AiMessage> assembled = messages.stream().map(SpringAiMessageAdapter::toCore).toList();
        boolean guardedUserPresent = guardedUserIndex >= 0;
        if (guardedUserPresent && (guardedUserIndex >= assembled.size()
                || !(assembled.get(guardedUserIndex) instanceof AiMessage.User))) {
            throw new IllegalArgumentException("guardedUserIndex must identify a user message");
        }
        AiMessage.User input = guardedUserPresent
                ? (AiMessage.User) assembled.get(guardedUserIndex) : new AiMessage.User("");
        AgentInputGuardrailChain.Outcome outcome = guardrails.evaluate(
                new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.MODEL,
                        input, assembled, scope, Map.of("model", request.modelName())));
        observability.recordGuardrails(scope.requestId(), "model_input",
                outcome.decisions(), outcome.refusal());
        if (!outcome.allowed()) throw new AgentInputRefusedException(outcome.refusal());
        if (!guardedUserPresent) return messages;
        List<Message> rewritten = new ArrayList<>(messages);
        rewritten.set(guardedUserIndex, SpringAiUserMessageAdapter.toSpring(outcome.input()));
        return List.copyOf(rewritten);
    }

    int lastUserMessageIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) return index;
        }
        return -1;
    }
}
