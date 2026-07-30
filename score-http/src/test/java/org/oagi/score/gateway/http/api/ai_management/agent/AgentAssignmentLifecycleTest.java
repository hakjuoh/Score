package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAssignmentLifecycleTest {

    @Test
    void leavesStartedLifecycleContentEmptyWhenTheEventTypeCarriesTheState() {
        AiWorkflowPlan.AgentTask task = task(null, null, null);

        assertThat(AgentAssignmentLifecycle.status(task, false)).isEmpty();
    }

    @Test
    void preservesSpecificLifecycleNarrationWithoutInventingGenericWorkingText() {
        assertThat(AgentAssignmentLifecycle.status(
                task("I’m checking the release.", null, null), false))
                .isEqualTo("I’m checking the release.");
        assertThat(AgentAssignmentLifecycle.status(
                task(null, "Checking", null), false))
                .isEqualTo("Checking.");
        assertThat(AgentAssignmentLifecycle.status(
                task(null, null, null), true))
                .isEqualTo("Completed.");
    }

    private AiWorkflowPlan.AgentTask task(String guideMessage, String activeVerb,
                                          String completedVerb) {
        return new AiWorkflowPlan.AgentTask(
                "evidence-researcher", "Evidence", "Check the current evidence.",
                guideMessage, activeVerb, completedVerb, AiWorkflowPlan.ToolAccess.READ_ONLY);
    }
}
