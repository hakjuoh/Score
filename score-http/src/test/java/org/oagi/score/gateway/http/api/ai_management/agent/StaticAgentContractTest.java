package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationCompactor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAgentContractTest {

    @Test
    void everyStaticPipelineAgentImplementsTheCommonAgentContract() {
        assertThat(GatewayAgent.class).isAssignableTo(Agent.class);
        assertThat(AssistantAgent.class).isAssignableTo(WorkflowAgent.class);
        assertThat(PlannerAgent.class).isAssignableTo(WorkflowAgent.class);
        assertThat(EvaluatorAgent.class).isAssignableTo(WorkflowAgent.class);
        assertThat(ConversationCompactor.class).isAssignableTo(Agent.class);
        assertThat(ResponseOnlyAgent.class).isAssignableTo(Agent.class);
        assertThat(DefinitionGeneratorAgent.class).isAssignableTo(Agent.class);
        assertThat(NameSuggesterAgent.class).isAssignableTo(Agent.class);
        assertThat(SynthesizerAgent.class).isAssignableTo(WorkflowAgent.class);
        assertThat(ResolvedAgent.class).isAssignableTo(Agent.class);
    }

    @Test
    void everyCatalogBackedComponentRegistrationResolvesItsSystemDefinition() {
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());

        List.of(GatewayAgent.class, ResponseOnlyAgent.class,
                DefinitionGeneratorAgent.class, NameSuggesterAgent.class,
                SynthesizerAgent.class, PlannerAgent.class,
                EvaluatorAgent.class, ConversationCompactor.class).forEach(type -> {
            String registration = type.getAnnotation(Component.class).value();
            assertThat(catalog.systemDefinition(registration).id().value())
                    .isEqualTo(registration);
        });
    }
}
