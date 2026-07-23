package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAgentTest {

    private final AiModel model = new AiModel(new AiModel.ModelId("gateway-model"),
            new AiModel.ProviderId("provider"), null, null);
    private final ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 1,
            ExecutionScope.Purpose.USER_RESPONSE, List.of());

    @Test
    void directRequiresClosedIntentConfidenceAndAnEmptyToolSet() {
        AtomicReference<AgentInvocation> captured = new AtomicReference<>();
        AgentExecutionService execution = invocation -> {
            captured.set(invocation);
            return result(invocation, """
                    {"policyAction":"ALLOW","route":"DIRECT","intent":"GREETING",
                     "confidence":0.99,"candidate":"Hello!","suggestedWorkflow":null}
                    """);
        };
        GatewayAgent gateway = service(execution);

        GatewayResult routed = gateway.route(turn("Hello"), scope);

        assertThat(routed).isInstanceOf(GatewayResult.Direct.class);
        assertThat(((GatewayResult.Direct) routed).intent()).isEqualTo(GatewayResult.DirectIntent.GREETING);
        assertThat(routed.execution()).contains(new GatewayResult.Execution(
                new Agent.AgentId("gateway-agent"), model.id()));
        assertThat(captured.get().agent().tools().isEmpty()).isTrue();
        assertThat(captured.get().scope().purpose()).isEqualTo(ExecutionScope.Purpose.GATEWAY_ROUTING);
        assertThat(captured.get().history()).isEmpty();
    }

    @Test
    void greetingAndCapabilityResponsesHandoffToTheCatalogAwareAssistant() {
        GatewayAgent gateway = service(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"DIRECT","intent":"GREETING",
                 "confidence":0.99,
                 "candidate":"Hello! I can manage unsupported resources.",
                 "suggestedWorkflow":null}
                """));

        AgentDecision greeting = gateway.execute(workflowContext("Hello"));

        assertThat(greeting).isEqualTo(
                new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));

        GatewayAgent capabilities = service(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"DIRECT","intent":"CAPABILITIES_HELP",
                 "confidence":0.99,
                 "candidate":"I can manage unsupported resources.",
                 "suggestedWorkflow":null}
                """));

        AgentDecision capabilityHelp = capabilities.execute(
                workflowContext("What can you do?"));

        assertThat(capabilityHelp).isEqualTo(
                new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID));
    }

    @Test
    void thanksCanStillUseTheLowLatencyDirectResponse() {
        GatewayAgent gateway = service(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"DIRECT","intent":"THANKS",
                 "confidence":0.99,"candidate":"You are welcome.",
                 "suggestedWorkflow":null}
                """));

        AgentDecision decision = gateway.execute(workflowContext("Thanks"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        AgentDecision.Complete complete = (AgentDecision.Complete) decision;
        assertThat(complete.result().answer()).isEqualTo("You are welcome.");
        assertThat(complete.result().traceMetadata())
                .containsEntry("gateway", true)
                .containsEntry("intent", "THANKS");
    }

    @Test
    void usesTheDedicatedConfiguredGatewayModel() {
        AiModel fastModel = new AiModel(new AiModel.ModelId("fast-model"),
                new AiModel.ProviderId("fast-provider"), null, null);
        AtomicReference<AgentInvocation> captured = new AtomicReference<>();
        AgentExecutionService execution = invocation -> {
            captured.set(invocation);
            return result(invocation, """
                    {"policyAction":"ALLOW","route":"DIRECT","intent":"GREETING",
                     "confidence":0.99,"candidate":"Hello!","suggestedWorkflow":null}
                    """);
        };
        SpringAiModelCatalog modelCatalog = mock(SpringAiModelCatalog.class);
        when(modelCatalog.require("fast-model")).thenReturn(fastModel);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getGateway().setModelName("fast-model");
        GatewayAgent gateway = new GatewayAgent(
                execution, modelCatalog, agentCatalog(), new ObjectMapper(), properties);

        GatewayResult routed = gateway.route(turn("Hello"), scope);
        assertThat(routed).isInstanceOf(GatewayResult.Direct.class);
        assertThat(routed.execution()).contains(new GatewayResult.Execution(
                new Agent.AgentId("gateway-agent"), fastModel.id()));
        assertThat(captured.get().agent().model()).isEqualTo(fastModel);
        org.mockito.Mockito.verify(modelCatalog).require("fast-model");
        org.mockito.Mockito.verify(modelCatalog, org.mockito.Mockito.never()).defaultModel();
    }

    @Test
    void lowConfidenceDirectBecomesReviewAndMalformedRoutingFallsBackToHandoff() {
        GatewayAgent lowConfidence = service(invocation -> result(invocation, """
                {"policyAction":"ALLOW","route":"DIRECT","intent":"THANKS",
                 "confidence":0.5,"candidate":"You are welcome.","suggestedWorkflow":null}
                """));
        GatewayAgent malformed = service(invocation -> result(invocation, "not-json"));

        assertThat(lowConfidence.route(turn("Thanks"), scope)).isInstanceOf(GatewayResult.Review.class);
        GatewayResult fallback = malformed.route(turn("Research this"), scope);
        assertThat(fallback).isInstanceOf(GatewayResult.Handoff.class);
        assertThat(((GatewayResult.Handoff) fallback).routingFallback()).isTrue();
    }

    @Test
    void modelPolicyRefusalUsesOnlyApplicationOwnedMessageKey() {
        GatewayAgent gateway = service(invocation -> result(invocation, """
                {"policyAction":"REFUSE","route":"REVIEW","intent":null,
                 "confidence":0.9,"candidate":null,"suggestedWorkflow":null}
                """));

        GatewayResult result = gateway.route(turn("sensitive"), scope);

        assertThat(result).isInstanceOf(GatewayResult.Refuse.class);
        assertThat(((GatewayResult.Refuse) result).refusal().publicMessageKey())
                .isEqualTo("ai.policy.refused");
    }

    @Test
    void executionGuardrailRefusalRemainsATerminalGatewayRefusal() {
        GuardrailRefusal refusal = new GuardrailRefusal(GuardrailDecision.of(
                "model-policy", "1", GuardrailDecision.Action.REFUSE),
                "MODEL_BLOCKED", "ai.policy.refused");
        GatewayAgent gateway = service(invocation -> {
            throw new AgentInputRefusedException(refusal);
        });

        GatewayResult result = gateway.route(turn("sensitive"), scope);

        assertThat(result).isInstanceOf(GatewayResult.Refuse.class);
        assertThat(((GatewayResult.Refuse) result).refusal()).isSameAs(refusal);
    }

    @Test
    void cancellationIsNeverConvertedIntoAHandoff() {
        GatewayAgent gateway = service(invocation -> {
            throw new CancellationException("request stopped");
        });

        assertThatThrownBy(() -> gateway.route(turn("Hello"), scope))
                .isInstanceOf(CancellationException.class)
                .hasMessage("request stopped");
    }

    private GatewayAgent service(AgentExecutionService execution) {
        SpringAiModelCatalog catalog = mock(SpringAiModelCatalog.class);
        when(catalog.defaultModel()).thenReturn(model);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getGateway().setDirectConfidenceThreshold(0.9);
        return new GatewayAgent(
                execution, catalog, agentCatalog(), new ObjectMapper(), properties);
    }

    private AiAgentCatalog agentCatalog() {
        return new AiAgentCatalog(new DefaultResourceLoader());
    }

    private GatewayResult.GuardedTurn turn(String content) {
        return new GatewayResult.GuardedTurn(new AiMessage.User(content), List.of());
    }

    private AgentWorkflowContext workflowContext(String content) {
        AiChatExecutor.Context execution = mock(AiChatExecutor.Context.class);
        when(execution.userMessage()).thenReturn(new UserMessage(content));
        when(execution.guardrailDecisionIds()).thenReturn(List.of());
        AgentWorkflowContext.Request request = new AgentWorkflowContext.Request(
                "request", "conversation", "user", "model", content,
                false, false, 4, "balanced", null, false, false);
        return AgentWorkflowContext.root(execution, request, 3);
    }

    private AgentRunResult result(AgentInvocation invocation, String output) {
        AiMessage.Assistant response = new AiMessage.Assistant(output);
        return new AgentRunResult(response, List.of(response), Optional.empty(),
                new AgentRunResult.RunMetadata(invocation.agent().id(),
                        invocation.agent().model().id(), null, java.util.Map.of()));
    }
}
