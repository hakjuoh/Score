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

    @Test
    void policyDisabledMultiAgentCannotBecomeExplicitDelegation() {
        var disabled = WorkflowRequestAdapter.from(context(
                "Spawn exactly 3 sub-agents in parallel.", "assistant", false, 3));

        assertThat(disabled.maximumAgents()).isEqualTo(1);
        assertThat(disabled.delegationRequested()).isFalse();
        assertThat(disabled.explicitDelegationRequested()).isFalse();
    }

    @Test
    void automaticModeExposesPolicyCapacityWithoutForcingDelegation() {
        var automatic = WorkflowRequestAdapter.from(context(
                "Create and profile several related records.", null, false, 2));

        assertThat(automatic.maximumAgents()).isEqualTo(2);
        assertThat(automatic.delegationRequested()).isFalse();
        assertThat(automatic.explicitDelegationRequested()).isFalse();
    }

    @Test
    void activeUiSelectionOverridesAStoredAssistantPreference() {
        var active = WorkflowRequestAdapter.from(context(
                "Inspect related records.", "assistant", true, 2));

        assertThat(active.maximumAgents()).isEqualTo(2);
        assertThat(active.delegationRequested()).isTrue();
    }

    private ChatExecutionContext context(String prompt, String activeWorkflow,
                                         boolean multiAgentActive) {
        return context(prompt, activeWorkflow, multiAgentActive, 3);
    }

    private ChatExecutionContext context(String prompt, String activeWorkflow,
                                         boolean multiAgentActive, int maximumAgents) {
        AiMultiAgentOptions options = maximumAgents == 1
                ? AiMultiAgentOptions.single()
                : new AiMultiAgentOptions(multiAgentActive, maximumAgents, "balanced");
        ChatRequest request = new ChatRequest(prompt, "request-1", null,
                "conversation-1", null, List.of(), null, "model", null, null,
                options, activeWorkflow, null);
        return ChatExecutionContext.fromCoreMessages(request, List.of(),
                new AiMessage.User(prompt), null, null, false, false,
                AgentToolPolicy.NONE, 0);
    }
}
