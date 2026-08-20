package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.support.TestAgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayAgentTest {

    private final AiModel model = new AiModel(new AiModel.ModelId("gateway-model"),
            new AiModel.ProviderId("provider"), null, null);

    @Test
    void oneRunnerExecutesTheGatewayDefinitionAndKeepsItsToolsEmpty() {
        AtomicReference<AgentInvocation> captured = new AtomicReference<>();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> {
            captured.set(invocation);
            return result(invocation, """
                    {"policyAction":"ALLOW","route":"DIRECT","intent":"THANKS",
                     "confidence":0.99,"candidate":"You are welcome.","suggestedWorkflow":null}
                    """);
        });
        GatewayAgent gateway = gateway();

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(),
                workflowContext("Thanks"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(((AgentDecision.Complete) decision).result().content())
                .isEqualTo("You are welcome.");
        assertThat(captured.get().session().tools().isEmpty()).isTrue();
        assertThat(captured.get().scope().purpose())
                .isEqualTo(ExecutionScope.Purpose.GATEWAY_ROUTING);
    }

    @Test
    void greetingsAndCapabilityQuestionsAreHandedToTheAssistantDefinition() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"DIRECT","intent":"GREETING",
                 "confidence":0.99,"candidate":"Hello!","suggestedWorkflow":null}
                """));

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(),
                workflowContext("Hello"));

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
    }

    @Test
    void malformedOrLowConfidenceRoutingFallsBackToTheAssistant() {
        GatewayAgent gateway = gateway();
        AgentExecutionService malformed = TestAgentExecutionService.model(
                invocation -> result(invocation, "not-json"));
        AgentExecutionService uncertain = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"DIRECT","intent":"THANKS",
                 "confidence":0.5,"candidate":"You are welcome.","suggestedWorkflow":null}
                """));

        assertThat(runner(malformed, gateway).run(gateway.callId(), workflowContext("Thanks")))
                .isEqualTo(new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
        assertThat(runner(uncertain, gateway).run(gateway.callId(), workflowContext("Thanks")))
                .isEqualTo(new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
    }

    @Test
    void policyRefusalAndCancellationRemainTerminal() {
        GatewayAgent gateway = gateway();
        AgentExecutionService refusal = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"REFUSE","route":"REVIEW","intent":null,
                 "confidence":0.9,"candidate":null,"suggestedWorkflow":null}
                """));
        assertThatThrownBy(() -> runner(refusal, gateway).run(gateway.callId(),
                workflowContext("sensitive")))
                .isInstanceOf(AgentGuardrailRefusedException.class);

        AgentExecutionService cancellation = TestAgentExecutionService.model(invocation -> {
            throw new CancellationException("request stopped");
        });
        assertThatThrownBy(() -> runner(cancellation, gateway).run(gateway.callId(),
                workflowContext("Hello")))
                .isInstanceOf(CancellationException.class)
                .hasMessage("request stopped");
    }

    @Test
    void complexRequestsWithSuggestedAgentsWorkflowAreHandedToThePlanner() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"HANDOFF","intent":null,
                 "confidence":0.95,"candidate":null,"suggestedWorkflow":"agents"}
                """));

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(),
                workflowContext("Create and profile a BIE with multiple elements"));

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(PlannerAgent.PLANNER_ID));
    }

    @Test
    void simpleRequestsWithSuggestedAssistantWorkflowAreHandedToTheAssistant() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"HANDOFF","intent":null,
                 "confidence":0.95,"candidate":null,"suggestedWorkflow":"assistant"}
                """));

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(),
                workflowContext("What is the state of Invoice BIE?"));

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
    }

    @Test
    void complexRequestsWithPolicyDisabledMultiAgentAreHandedToTheAssistant() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"HANDOFF","intent":null,
                 "confidence":0.95,"candidate":null,"suggestedWorkflow":"agents"}
                """));

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(),
                workflowContextWithMaxAgents("Create and profile a BIE", 1));

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
    }

    @Test
    void explicitNegationTakesPrecedenceOverSuggestedAgentsWorkflow() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = mock(AgentExecutionService.class);

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(),
                workflowContext("Create a BIE without subagents"));

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
        verifyNoInteractions(execution);
    }

    @Test
    void explicitDelegationTakesPrecedenceAndHandsOffToPlanner() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"HANDOFF","intent":null,
                 "confidence":0.95,"candidate":null,"suggestedWorkflow":"assistant"}
                """));

        ChatRequest chatRequest = new ChatRequest("Create a BIE", "request", "gateway-agent",
                "conversation", null, List.of(), null, model.id().value(), null, null);
        AgentWorkflowContext.Request request = new AgentWorkflowContext.Request(
                "request", "conversation", "user", model.id().value(), "Create a BIE",
                false, false, 4, "balanced", null, true, true, false);
        AgentWorkflowContext context = AgentWorkflowContext.root(ChatExecutionContext.fromRequest(chatRequest, List.of(),
                new UserMessage("Create a BIE"), null, null, false, false), request, 3);

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(), context);

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(PlannerAgent.PLANNER_ID));
    }

    @Test
    void explicitDelegationFactDoesNotDependOnPersistentWorkflowActivation() {
        GatewayAgent gateway = gateway();
        AgentExecutionService execution = mock(AgentExecutionService.class);

        ChatRequest chatRequest = new ChatRequest("Use sub-agents", "request", "gateway-agent",
                "conversation", null, List.of(), null, model.id().value(), null, null);
        AgentWorkflowContext.Request request = new AgentWorkflowContext.Request(
                "request", "conversation", "user", model.id().value(), "Use sub-agents",
                false, false, 4, "balanced", null, false, true, false);
        AgentWorkflowContext context = AgentWorkflowContext.root(ChatExecutionContext.fromRequest(chatRequest, List.of(),
                new UserMessage("Use sub-agents"), null, null, false, false), request, 3);

        AgentDecision decision = runner(execution, gateway).run(gateway.callId(), context);

        assertThat(decision).isEqualTo(new AgentDecision.Handoff(PlannerAgent.PLANNER_ID));
        verifyNoInteractions(execution);
    }

    private GatewayAgent gateway() {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getGateway().setDirectConfidenceThreshold(0.9);
        return new GatewayAgent(new AiAgentCatalog(new DefaultResourceLoader()),
                new ObjectMapper(), properties);
    }

    private AgentRunner runner(AgentExecutionService execution, GatewayAgent gateway) {
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require(model.id().value())).thenReturn(model);
        return new AgentRunner(execution, models, null, null, List.of(gateway));
    }

    private AgentWorkflowContext workflowContext(String content) {
        return workflowContextWithMaxAgents(content, 4);
    }

    private AgentWorkflowContext workflowContextWithMaxAgents(String content, int maxAgents) {
        ChatRequest chatRequest = new ChatRequest(content, "request", "gateway-agent",
                "conversation", null, List.of(), null, model.id().value(), null, null);
        AgentWorkflowContext.Request request = new AgentWorkflowContext.Request(
                "request", "conversation", "user", model.id().value(), content,
                false, false, maxAgents, "balanced", null, false, false, false);
        return AgentWorkflowContext.root(ChatExecutionContext.fromRequest(chatRequest, List.of(),
                new UserMessage(content), null, null, false, false), request, 3);
    }

    private AgentRunResult result(AgentInvocation invocation, String output) {
        AiMessage.Assistant response = new AiMessage.Assistant(output);
        return new AgentRunResult(response, List.of(response), Optional.empty(),
                new AgentRunResult.RunMetadata(invocation.session().agent().id(),
                        invocation.session().model().id(), null, Map.of()));
    }
}
