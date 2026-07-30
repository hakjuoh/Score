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
                "Spawn exactly 3 sub-agents in parallel.", "agents", false));
        var inherited = WorkflowRequestAdapter.from(context(
                "Inspect the current release.", "agents", false));

        assertThat(explicit.delegationRequested()).isTrue();
        assertThat(explicit.explicitDelegationRequested()).isTrue();
        assertThat(inherited.delegationRequested()).isTrue();
        assertThat(inherited.explicitDelegationRequested()).isFalse();
    }

    @Test
    void ignoresRemovedWorkflowPreferenceNamesWhenMultiAgentIsInactive() {
        var removed = WorkflowRequestAdapter.from(context(
                "Inspect the current release.", "orchestrator_workers", false));
        var unknown = WorkflowRequestAdapter.from(context(
                "Inspect the current release.", "future-mode", false));

        assertThat(removed.delegationRequested()).isFalse();
        assertThat(unknown.delegationRequested()).isFalse();
    }

    private ChatExecutionContext context(String prompt, String activeWorkflow,
                                         boolean multiAgentActive) {
        ChatRequest request = new ChatRequest(prompt, "request-1", null,
                "conversation-1", null, List.of(), null, "model", null, null,
                new AiMultiAgentOptions(multiAgentActive, 3, "balanced"), activeWorkflow, null);
        return ChatExecutionContext.fromCoreMessages(request, List.of(),
                new AiMessage.User(prompt), null, null, false, false,
                AgentToolPolicy.NONE, 0);
    }
}
