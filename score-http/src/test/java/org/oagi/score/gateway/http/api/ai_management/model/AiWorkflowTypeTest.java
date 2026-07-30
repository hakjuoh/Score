package org.oagi.score.gateway.http.api.ai_management.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkflowTypeTest {

    @Test
    void classifiesAOneMemberWorkflowAsSequential() {
        assertThat(AiWorkflowType.from(workflow(List.of(member("only")), List.of())))
                .isEqualTo(AiWorkflowType.SEQUENTIAL);
    }

    @Test
    void classifiesATotallyOrderedGraphAsSequential() {
        var workflow = workflow(
                List.of(member("first"), member("second"), member("third")),
                List.of(edge("first", "second"), edge("second", "third")));

        assertThat(AiWorkflowType.from(workflow)).isEqualTo(AiWorkflowType.SEQUENTIAL);
    }

    @Test
    void classifiesIndependentMembersAsParallel() {
        var workflow = workflow(
                List.of(member("first"), member("second")), List.of());

        assertThat(AiWorkflowType.from(workflow)).isEqualTo(AiWorkflowType.PARALLEL);
    }

    @Test
    void classifiesFanOutJoinAsParallel() {
        var workflow = workflow(
                List.of(member("first"), member("second"), member("join")),
                List.of(edge("first", "join"), edge("second", "join")));

        assertThat(AiWorkflowType.from(workflow)).isEqualTo(AiWorkflowType.PARALLEL);
    }

    @Test
    void classifiesALaterFanOutLayerAsParallel() {
        var workflow = workflow(
                List.of(member("root"), member("left"), member("right")),
                List.of(edge("root", "left"), edge("root", "right")));

        assertThat(AiWorkflowType.from(workflow)).isEqualTo(AiWorkflowType.PARALLEL);
    }

    private AiWorkflowPlan.WorkflowDefinition workflow(
            List<AiWorkflowPlan.Member> members, List<AiWorkflowPlan.Edge> edges) {
        return new AiWorkflowPlan.WorkflowDefinition("work", members, edges);
    }

    private AiWorkflowPlan.Member member(String id) {
        return new AiWorkflowPlan.Member(id,
                new AiWorkflowPlan.AgentTask("agent-" + id, id, "Handle " + id,
                        "Handling " + id + ".", "Handling", "Handled",
                        AiWorkflowPlan.ToolAccess.NONE), null);
    }

    private AiWorkflowPlan.Edge edge(String from, String to) {
        return new AiWorkflowPlan.Edge(from, to);
    }
}
