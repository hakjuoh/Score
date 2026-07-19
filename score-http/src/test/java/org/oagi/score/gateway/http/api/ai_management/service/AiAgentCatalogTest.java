package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentCatalogTest {

    @Test
    void loadsSelfContainedMarkdownAgents() {
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());

        assertThat(catalog.all()).extracting(AiAgentDefinition::id)
                .containsExactly("critical-reviewer", "evidence-researcher", "general-purpose");
        assertThat(catalog.all()).allSatisfy(agent -> {
            assertThat(agent.prompt()).isNotBlank();
            assertThat(agent.toolPolicy()).isEqualTo(AiRuntime.ToolPolicy.READ_ONLY);
        });
        assertThat(catalog.require("evidence-researcher").prompt())
                .startsWith("You are an isolated, read-only connectCenter evidence researcher.")
                .contains("A failed tool call is not evidence that a record does not exist.")
                .doesNotContain("---")
                .isNotEqualTo(catalog.require("critical-reviewer").prompt());
        assertThat(catalog.require("critical-reviewer").prompt())
                .contains("`SUPPORTED`, `PARTIAL`, or `UNSUPPORTED`");
        assertThat(catalog.plannerRegistry())
                .contains("- evidence-researcher: Finds and verifies current connectCenter records")
                .doesNotContain("toolPolicy", "You are a read-only");
    }
}
