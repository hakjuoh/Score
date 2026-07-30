package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRequestAdapterTest {

    @Test
    void distinguishesCurrentTurnDelegationFromAnActiveConversationPreference() {
        var explicit = WorkflowRequestAdapter.from(context(
                "Spawn exactly 3 sub-agents in parallel.", "agents"));
        var inherited = WorkflowRequestAdapter.from(context(
                "Inspect the current release.", "agents"));

        assertThat(explicit.delegationRequested()).isTrue();
        assertThat(explicit.explicitDelegationRequested()).isTrue();
        assertThat(inherited.delegationRequested()).isTrue();
        assertThat(inherited.explicitDelegationRequested()).isFalse();
    }

    private ChatExecutionContext context(String prompt, String activeWorkflow) {
        ChatRequest request = new ChatRequest(prompt, "request-1", null,
                "conversation-1", null, List.of(), null, "model", null, null,
                new AiMultiAgentOptions(true, 3, "balanced"), activeWorkflow, null);
        return ChatExecutionContext.fromCoreMessages(request, List.of(),
                new AiMessage.User(prompt), null, null, false, false,
                AgentToolPolicy.NONE, 0);
    }
}
