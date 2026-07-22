package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkflowIntentTest {

    @Test
    void activatesTwoAgentsForAnExplicitFanOutRequest() {
        ChatRequest request = request("Compare Sync Purchase Order with Get Purchase Order in 10.13. "
                + "Fan out to sub-agents and delegate one record to each agent.",
                AiMultiAgentOptions.single());

        ChatRequest resolved = AiWorkflowIntent.applyExplicitDelegation(request);

        assertThat(resolved.activeWorkflow()).isEqualTo("parallel");
        assertThat(resolved.multiAgent()).isEqualTo(new AiMultiAgentOptions(true, 2, "balanced"));
        assertThat(AiWorkflowIntent.comparisonWorkItems(request.prompt()))
                .containsExactly("Sync Purchase Order", "Get Purchase Order");
    }

    @Test
    void letsExplicitPromptCountsOverrideLegacyClientSettings() {
        ChatRequest three = AiWorkflowIntent.applyExplicitDelegation(request(
                "Spawn three subagents and delegate these checks in parallel.",
                AiMultiAgentOptions.single()));
        assertThat(three.multiAgent().maxAgents()).isEqualTo(3);

        ChatRequest configured = request("Spawn two agents in parallel.",
                new AiMultiAgentOptions(true, 4, "verification"));
        ChatRequest resolved = AiWorkflowIntent.applyExplicitDelegation(configured);
        assertThat(resolved.multiAgent())
                .isEqualTo(new AiMultiAgentOptions(true, 2, "verification"));
        assertThat(resolved.activeWorkflow()).isEqualTo("parallel");
    }

    @Test
    void treatsUsingSubAgentsAsAnExplicitAgentWorkflowOnEveryTurn() {
        ChatRequest first = request("create a sample business context using sub agents",
                AiMultiAgentOptions.single());
        ChatRequest followUp = request("add values using sub-agents",
                AiMultiAgentOptions.single());

        assertThat(AiWorkflowIntent.explicitlyRequestsAgents(first.prompt())).isTrue();
        assertThat(AiWorkflowIntent.explicitlyRequestsAgents(followUp.prompt())).isTrue();
        assertThat(AiWorkflowIntent.explicitlyRequestsFanOut(first.prompt())).isFalse();
        assertThat(AiWorkflowIntent.applyExplicitDelegation(followUp).activeWorkflow())
                .isEqualTo("orchestrator_workers");
    }

    @Test
    void doesNotActivateForOrdinaryParallelQueriesOrNegatedDelegation() {
        ChatRequest queries = request("Read both records in parallel and compare them.",
                AiMultiAgentOptions.single());
        ChatRequest negated = request("Do not use sub-agents or fan out; compare them yourself.",
                AiMultiAgentOptions.single());

        assertThat(AiWorkflowIntent.applyExplicitDelegation(queries)).isSameAs(queries);
        assertThat(AiWorkflowIntent.applyExplicitDelegation(negated)).isSameAs(negated);
        assertThat(AiWorkflowIntent.explicitlyRequestsAgents(negated.prompt())).isFalse();
    }

    @Test
    void extractsEnglishComparisonSubjectsForTaskLabels() {
        assertThat(AiWorkflowIntent.comparisonWorkItems(
                "Compare Sync Purchase Order with Get Purchase Order in release 10.13."))
                .containsExactly("Sync Purchase Order", "Get Purchase Order");
    }

    @Test
    void resolvesPersistentAgentPreferencesToAnActiveWorkflow() {
        var enabled = AiWorkflowIntent.persistentWorkflowCommand(
                "Use sub-agents for the following prompts").orElseThrow();
        var disabled = AiWorkflowIntent.persistentWorkflowCommand(
                "Never use sub-agents for future requests").orElseThrow();
        var automatic = AiWorkflowIntent.persistentWorkflowCommand(
                "Choose the workflow automatically from now on").orElseThrow();

        assertThat(enabled.activeWorkflow()).isEqualTo("orchestrator_workers");
        assertThat(enabled.acknowledgement()).startsWith("Understood. I’ll use sub-agents");
        assertThat(disabled.activeWorkflow()).isEqualTo("direct");
        assertThat(disabled.acknowledgement()).startsWith("Understood. I won’t use sub-agents");
        assertThat(automatic.activeWorkflow()).isNull();
    }

    @Test
    void keepsPerTurnAgentWordingOutOfPersistentConversationPreferences() {
        assertThat(AiWorkflowIntent.persistentWorkflowCommand(
                "Add values using sub-agents")).isEmpty();
        assertThat(AiWorkflowIntent.persistentWorkflowCommand(
                "Use sub-agents for the next request")).isEmpty();
    }

    private ChatRequest request(String prompt, AiMultiAgentOptions options) {
        return new ChatRequest(prompt, "request-1", null, "conversation-1", null,
                List.of(), null, "model", "high", "ask", options, null, null);
    }
}
