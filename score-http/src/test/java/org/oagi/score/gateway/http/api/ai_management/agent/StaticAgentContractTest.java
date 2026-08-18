package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationCompactor;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowRunner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAgentContractTest {

    @Test
    void oneRunnerExecutesManyDefinitionOnlyAgents() {
        assertThat(AgentRunner.class.isInterface()).isFalse();
        assertThat(java.lang.reflect.Modifier.isFinal(AgentRunner.class.getModifiers())).isTrue();
        assertThat(java.util.Arrays.stream(AgentRunner.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("execute"))).isTrue();
        assertThat(java.util.Arrays.stream(WorkflowRunner.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("executeAgent"))).isTrue();
        assertThat(AgentRunner.class.isAssignableFrom(Agent.class)).isFalse();
        assertThat(GatewayAgent.class).isAssignableTo(Agent.class);
        assertThat(AssistantAgent.class).isAssignableTo(Agent.class);
        assertThat(PlannerAgent.class).isAssignableTo(Agent.class);
        assertThat(EvaluatorAgent.class).isAssignableTo(Agent.class);
        assertThat(Agent.class.isAssignableFrom(ConversationCompactor.class)).isFalse();
        assertThat(ResponseOnlyAgent.class).isAssignableTo(Agent.class);
        assertThat(DefinitionGeneratorAgent.class).isAssignableTo(Agent.class);
        assertThat(NameSuggesterAgent.class).isAssignableTo(Agent.class);
        assertThat(ConversationTitlerAgent.class).isAssignableTo(Agent.class);
        assertThat(SynthesizerAgent.class).isAssignableTo(Agent.class);
        assertThat(Agent.class.isAssignableFrom(AgentSession.class)).isFalse();
        List.of(GatewayAgent.class, AssistantAgent.class, PlannerAgent.class,
                EvaluatorAgent.class, SynthesizerAgent.class)
                .forEach(type -> assertThat(AgentRunner.class.isAssignableFrom(type)).isFalse());
    }

    @Test
    void everyCatalogBackedComponentRegistrationResolvesItsSystemDefinition() {
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());

        List.of(GatewayAgent.class, ResponseOnlyAgent.class,
                DefinitionGeneratorAgent.class, NameSuggesterAgent.class,
                ConversationTitlerAgent.class,
                SynthesizerAgent.class, PlannerAgent.class,
                EvaluatorAgent.class, ConversationCompactor.class).forEach(type -> {
            String registration = type.getAnnotation(Component.class).value();
            assertThat(catalog.systemDefinition(registration).id().value())
                    .isEqualTo(registration);
        });
    }

    @Test
    void publicExecutionSeamsDoNotExposeProviderContextAndModelExecutionRemainsFunctional()
            throws ClassNotFoundException {
        Class<?> providerContext = Class.forName(
                AiChatExecutor.class.getName() + "$Context");
        assertThat(Modifier.isPublic(providerContext.getModifiers())).isFalse();
        assertThat(AgentExecutionService.class.isAnnotationPresent(
                FunctionalInterface.class)).isTrue();
        assertThat(Modifier.isAbstract(
                getMethod(AgentExecutionService.class, "executeChat").getModifiers())).isFalse();
        assertThat(java.util.Arrays.stream(ChatExecutionContext.class.getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(method.getReturnType()),
                        java.util.Arrays.stream(method.getParameterTypes())))
                .noneMatch(type -> type == providerContext)).isTrue();
        assertThat(java.util.Arrays.stream(ChatExecutionContext.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .noneMatch(type -> type == providerContext
                        || type == AiChatExecutor.class)).isTrue();
    }

    private java.lang.reflect.Method getMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name, type == AgentExecutionService.class
                    ? org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession.class
                    : Object.class);
        } catch (NoSuchMethodException ignored) {
            return java.util.Arrays.stream(type.getMethods())
                    .filter(method -> method.getName().equals(name))
                    .findFirst().orElseThrow();
        }
    }
}
