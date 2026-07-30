package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentCatalogTest {

    @Test
    void loadsSelfContainedMarkdownAgents() {
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());

        assertThat(catalog.all()).extracting(AiAgentDefinition::id)
                .containsExactly("compactor-agent", "connectcenter-assistant",
                        "critical-reviewer", "definition-generator", "evidence-researcher",
                        "gateway-agent", "general-purpose", "name-suggester",
                        "response-only-agent", "workflow-evaluator", "workflow-planner",
                        "workflow-synthesizer");
        assertThat(catalog.workers()).allSatisfy(agent -> {
            assertThat(agent.instruction()).isNotBlank();
        });
        assertThat(catalog.workers()).extracting(AiAgentDefinition::id)
                .containsExactly("critical-reviewer", "evidence-researcher", "general-purpose");
        assertThat(catalog.runtimeDefinition("gateway-agent").instruction().value())
                .startsWith("Classify one safe user turn.");
        assertThatThrownBy(() -> catalog.requireWorker("gateway-agent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown worker Agent");
        assertThat(catalog.require("workflow-planner").instruction())
                .startsWith("You are the Planner Agent")
                .contains("Every workflow-level and Agent-level guideMessage")
                .doesNotContain("role: PLANNER");
        assertThat(catalog.require("workflow-synthesizer").instruction())
                .contains("Return the synthesized result to the parent workflow");
        assertThat(catalog.systemDefinition("gateway-agent").id().value())
                .isEqualTo("gateway-agent");
        assertThatThrownBy(() -> catalog.systemDefinition("general-purpose"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown system Agent");
        assertThat(catalog.require("evidence-researcher").instruction())
                .startsWith("You are an isolated connectCenter evidence researcher.")
                .contains("A failed tool call is not evidence that a record does not exist.")
                .doesNotContain("---")
                .isNotEqualTo(catalog.require("critical-reviewer").instruction());
        assertThat(catalog.require("critical-reviewer").instruction())
                .contains("`SUPPORTED`, `PARTIAL`, or `UNSUPPORTED`");
        assertThat(catalog.plannerRegistry())
                .contains("- evidence-researcher: Finds and verifies current connectCenter records")
                .doesNotContain("gateway-agent", "toolPolicy", "role:", "read-only");
        assertThat(catalog.workflowInstructionIds()).containsExactly(
                "workflow-original-request-reference", "workflow-upstream-results",
                "workflow-worker-assignment", "workflow-worker-full",
                "workflow-worker-restricted");
        assertThat(catalog.all()).extracting(AiAgentDefinition::id)
                .doesNotContainAnyElementsOf(catalog.workflowInstructionIds())
                .doesNotContainAnyElementsOf(catalog.executionInstructionIds());
        assertThat(catalog.executionInstructionIds()).containsExactly(
                "execution-approval-continuation",
                "execution-read-back-continuation",
                "execution-request-scoped-input",
                "execution-revised-mutation-continuation",
                "execution-textual-tool-call-recovery",
                "execution-ui-route-manifest-context");
        assertThat(catalog.workflowInstruction("workflow-worker-assignment").value())
                .contains("${assignment}");
        assertThatThrownBy(() -> catalog.workflowInstruction("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown AI instruction");
        assertThatThrownBy(() -> catalog.executionInstruction("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown AI execution instruction");
    }
}
