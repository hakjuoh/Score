package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowExecutorRegistryTest {

    @Test
    void directIsAlwaysInstalledAndAdvancedSupportComesOnlyFromRegistrations() {
        WorkflowExecutorRegistry registry = new WorkflowExecutorRegistry(List.of(registration("parallel")));

        assertThat(registry.installed()).extracting(WorkflowId::value)
                .containsExactlyInAnyOrder("direct", "parallel");
        registry.requireInstalled("parallel");
        assertThatThrownBy(() -> registry.requireInstalled("routing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not installed");
    }

    private WorkflowExecutorRegistry.Registration registration(String id) {
        return new WorkflowExecutorRegistry.Registration() {
            @Override public WorkflowId workflowId() { return new WorkflowId(id); }
            @Override public String description() { return id; }
        };
    }
}
