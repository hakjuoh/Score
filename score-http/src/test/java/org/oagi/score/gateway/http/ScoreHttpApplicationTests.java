package org.oagi.score.gateway.http;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class ScoreHttpApplicationTests {

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private List<Agent> agents;

    @Test
    public void contextLoads() {
        assertThat(applicationContext.getBeansOfType(AgentRunner.class))
                .as("one shared AgentRunner must execute every Agent definition")
                .hasSize(1);
        assertThat(agents).extracting(agent -> agent.id().value())
                .contains("gateway-agent", "connectcenter-assistant",
                        "workflow-planner", "workflow-evaluator",
                        "workflow-synthesizer", "response-only-agent",
                        "definition-generator", "name-suggester");
        assertThat(agents).allSatisfy(agent -> {
            assertThat(agentRunner.agent(agent.id().value()).id())
                    .isEqualTo(agent.id());
            assertThat(agentRunner.agent(agent.callId().value()).callId())
                    .isEqualTo(agent.callId());
        });
    }

}
