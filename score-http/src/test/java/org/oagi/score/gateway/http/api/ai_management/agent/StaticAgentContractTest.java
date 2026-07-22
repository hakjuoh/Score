package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationCompactor;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowEvaluator;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowPlanner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAgentContractTest {

    @Test
    void everyStaticPipelineAgentImplementsTheCommonAgentContract() {
        assertThat(GatewayAgent.class).isAssignableTo(Agent.class);
        assertThat(ConnectCenterAssistantAgent.class).isAssignableTo(Agent.class);
        assertThat(AiWorkflowPlanner.class).isAssignableTo(Agent.class);
        assertThat(AiWorkflowEvaluator.class).isAssignableTo(Agent.class);
        assertThat(ConversationCompactor.class).isAssignableTo(Agent.class);
        assertThat(ResponseOnlyAgent.class).isAssignableTo(Agent.class);
        assertThat(DefinitionGeneratorAgent.class).isAssignableTo(Agent.class);
        assertThat(NameSuggesterAgent.class).isAssignableTo(Agent.class);
        assertThat(WorkflowSynthesizerAgent.class).isAssignableTo(Agent.class);
        assertThat(ResolvedAgent.class).isAssignableTo(Agent.class);
    }

    @Test
    void everyCatalogBackedComponentRegistrationResolvesItsSystemDefinition() {
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());

        List.of(GatewayAgent.class, ResponseOnlyAgent.class,
                DefinitionGeneratorAgent.class, NameSuggesterAgent.class,
                WorkflowSynthesizerAgent.class, AiWorkflowPlanner.class,
                AiWorkflowEvaluator.class, ConversationCompactor.class).forEach(type -> {
            String registration = type.getAnnotation(Component.class).value();
            assertThat(catalog.systemDefinition(registration).id().value())
                    .isEqualTo(registration);
        });
    }
}
