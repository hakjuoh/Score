package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFactoryTest {

    @Test
    void bindsModelInstructionAndRequestAuthorizedTools() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("worker-agent"),
                "Worker", "Reads data",
                new AgentDefinition.InstructionTemplate("Stay grounded."));
        AiModel model = new AiModel(new AiModel.ModelId("model-1"),
                new AiModel.ProviderId("provider-1"), null, null);
        ToolSet available = new ToolSet(List.of(tool("read", AiTool.ToolEffect.READ_ONLY),
                tool("write", AiTool.ToolEffect.MUTATION)));

        AgentSession agent = AgentFactory.binding().create(definition, model, available);

        assertThat(agent.model()).isSameAs(model);
        assertThat(agent.instruction().value()).isEqualTo("Stay grounded.");
        assertThat(agent.tools().values()).extracting(value -> value.specification().name())
                .containsExactlyInAnyOrder("read", "write");
    }

    @Test
    void emptyToolSetIsAValidAgentSession() {
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("gateway-agent"),
                "Gateway", "Routes",
                new AgentDefinition.InstructionTemplate("Route safely."));
        AiModel model = new AiModel(new AiModel.ModelId("model-1"),
                new AiModel.ProviderId("provider-1"), null, null);

        assertThat(AgentFactory.binding().create(definition, model, null).tools().isEmpty())
                .isTrue();
    }

    @Test
    void rendersRegisteredPromptParametersAsLiteralValues() {
        AgentDefinition.InstructionTemplate instruction = new AgentDefinition.InstructionTemplate(
                "Request: ${request}; count=${count}");

        assertThat(instruction.render(Map.of("request", "$1 \\path", "count", 2)).value())
                .isEqualTo("Request: $1 \\path; count=2");
    }

    private AiTool tool(String name, AiTool.ToolEffect effect) {
        return new AiTool() {
            private final ToolSpecification specification = new ToolSpecification(new ToolId(name),
                    name, name, "{}", "{}", effect);
            @Override public ToolSpecification specification() { return specification; }
            @Override public ToolResult execute(ToolArguments arguments, ToolExecutionContext context) {
                return new ToolResult("{}");
            }
        };
    }
}
