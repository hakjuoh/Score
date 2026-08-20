package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentInstructionsTest {

    private final AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
    private final AgentInstructions instructions = new AgentInstructions(catalog);

    @Test
    void everyTypedTemplateExistsInTheCatalog() {
        assertThat(catalog.workflowInstructionIds()).containsAll(
                Arrays.stream(AgentInstructions.Template.values())
                        .map(AgentInstructions.Template::id).toList());
    }

    @Test
    void templateParametersAreStrict() {
        assertThat(instructions.render(AgentInstructions.Template.WORKER_ASSIGNMENT,
                Map.of("assignment", "Verify it.")).value()).contains("Verify it.");
        assertThatThrownBy(() -> instructions.render(
                AgentInstructions.Template.WORKER_ASSIGNMENT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerProgressRequiresVisibleGuidanceBeforeEveryToolRound() {
        assertThat(instructions.render(AgentInstructions.Template.WORKER_PROGRESS).value())
                .contains("Immediately before each Tool-use round",
                        "A guide sentence is not a final result",
                        "Never retry silently");
    }
}
