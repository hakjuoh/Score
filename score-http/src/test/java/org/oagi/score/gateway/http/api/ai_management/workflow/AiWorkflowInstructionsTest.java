package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiWorkflowInstructionsTest {

    private final AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
    private final AiWorkflowInstructions instructions = new AiWorkflowInstructions(catalog);

    @Test
    void resolvesEveryTypedWorkflowInstructionFromTheCommonCatalog() {
        assertThat(catalog.workflowInstructionIds()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(AiWorkflowInstructions.Template.values())
                        .map(AiWorkflowInstructions.Template::id).toList());
    }

    @Test
    void rendersDynamicValuesLiterally() {
        assertThat(instructions.render(AiWorkflowInstructions.Template.WORKER_ASSIGNMENT,
                Map.of("assignment", "Inspect $1 at C:\\records and preserve ${literal}.")).value())
                .isEqualTo("WORKER_ASSIGNMENT\nInspect $1 at C:\\records and preserve ${literal}.");
    }

    @Test
    void rejectsMissingOrUnusedWorkflowParameters() {
        assertThatThrownBy(() -> instructions.render(
                AiWorkflowInstructions.Template.WORKER_ASSIGNMENT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing=[assignment]");
        assertThatThrownBy(() -> instructions.render(
                AiWorkflowInstructions.Template.RESULT_TRUNCATED,
                Map.of("unused", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected=[unused]");
    }
}
