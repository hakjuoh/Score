package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.util.StringUtils;

import java.util.List;

/** Creates the canonical request scope shared by turn, compaction, and persistence paths. */
final class ChatExecutionScopes {

    private ChatExecutionScopes() {
    }

    static ExecutionScope turn(ChatRequest request, ScoreUser requester, long generation) {
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && StringUtils.hasText(requester.username())
                ? requester.username() : "unknown";
        return new ExecutionScope(request.requestId(), request.conversationId(), requesterId,
                Math.max(0L, generation), ExecutionScope.Purpose.USER_RESPONSE, List.of());
    }

    static ExecutionScope withDecisions(ExecutionScope scope,
                                        List<GuardrailDecision> decisions) {
        ExecutionScope result = scope;
        for (GuardrailDecision decision : decisions) {
            result = result.withDecision(decision.decisionId());
        }
        return result;
    }
}
