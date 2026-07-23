package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiUserMessageAdapter;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Low-latency no-Tool Agent for bounded simple-request routing and direct responses. */
@Component("gateway-agent")
public final class GatewayAgent extends CatalogBackedAgent implements WorkflowAgent {

    private final AgentExecutionService execution;
    private final SpringAiModelCatalog models;
    private final ObjectMapper objectMapper;
    private final ScoreAiProperties.Gateway configuration;
    private final AgentFactory factory = AgentFactory.binding();

    public GatewayAgent(AgentExecutionService execution, SpringAiModelCatalog models,
                        AiAgentCatalog agents, ObjectMapper objectMapper,
                        ScoreAiProperties properties) {
        super(agents);
        this.execution = execution; this.models = models;
        this.objectMapper = objectMapper;
        this.configuration = properties.getGateway();
    }

    public boolean enabled() { return configuration.isEnabled(); }

    @Override
    public AgentDecision execute(AgentWorkflowContext context) {
        if (context.request().mutationConfirmation()) {
            return new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID);
        }
        GatewayResult result = route(new GatewayResult.GuardedTurn(
                SpringAiUserMessageAdapter.toCore(context.execution().userMessage()), List.of()),
                executionScope(context), context.observationContext(), context);
        if (result instanceof GatewayResult.Direct direct) {
            if (direct.intent() != GatewayResult.DirectIntent.THANKS) {
                return new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("gateway", true);
            metadata.put("intent", direct.intent().name());
            metadata.put("confidence", direct.confidence());
            direct.execution().ifPresent(value -> {
                metadata.put("agentId", value.agentId().value());
                metadata.put("modelId", value.modelId().value());
                metadata.put("executionPurpose", ExecutionScope.Purpose.GATEWAY_ROUTING.name());
            });
            return new AgentDecision.Complete(new AiChatExecutor.Result(
                    direct.candidate().content(), Map.copyOf(metadata)));
        }
        if (result instanceof GatewayResult.Refuse refuse) {
            throw new AgentInputRefusedException(refuse.refusal(), id());
        }
        return new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID);
    }

    public GatewayResult route(GatewayResult.GuardedTurn turn, ExecutionScope parentScope) {
        return route(turn, parentScope, Map.of(), null);
    }

    private GatewayResult route(GatewayResult.GuardedTurn turn, ExecutionScope parentScope,
                                Map<String, Object> observationContext,
                                AgentWorkflowContext workflowContext) {
        if (!enabled()) return handoff(turn, 1.0d, true);
        if (turn.turn().content().length() > configuration.getMaximumInputCharacters()
                || !turn.turn().attachments().isEmpty()) {
            return handoff(turn, 1.0d, false);
        }
        GatewayResult.Execution gatewayExecution = null;
        try {
            AiModel model = StringUtils.hasText(configuration.getModelName())
                    ? models.require(configuration.getModelName()) : models.defaultModel();
            ResolvedAgent gateway = factory.create(definition(), model, ToolSet.empty());
            gatewayExecution = new GatewayResult.Execution(gateway.id(), model.id());
            ExecutionScope scope = parentScope.withPurpose(ExecutionScope.Purpose.GATEWAY_ROUTING);
            AgentRunResult result = execution.execute(new AgentInvocation(null, gateway, turn.turn(),
                    List.of(), scope, null, observationContext));
            if (workflowContext != null) {
                workflowContext.recordUsage(definition().name(), result);
            }
            return decode(turn, result.response().content(), gatewayExecution);
        } catch (AgentInputRefusedException refused) {
            return new GatewayResult.Refuse(refused.refusal(),
                    Optional.ofNullable(gatewayExecution));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailableOrMalformed) {
            return handoff(turn, 0.0d, true, gatewayExecution);
        }
    }

    private GatewayResult decode(GatewayResult.GuardedTurn turn, String json,
                                 GatewayResult.Execution execution) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception malformed) {
            return handoff(turn, 0.0d, true, execution);
        }
        String policy = text(root, "policyAction");
        if ("REFUSE".equals(policy)) {
            GuardrailDecision decision = GuardrailDecision.of("gateway-turn-policy", "1",
                    GuardrailDecision.Action.REFUSE);
            return new GatewayResult.Refuse(new GuardrailRefusal(decision,
                    "GATEWAY_POLICY_REFUSAL", "ai.policy.refused"), Optional.of(execution));
        }
        if (!"ALLOW".equals(policy)) return handoff(turn, 0.0d, true, execution);
        double confidence = root.path("confidence").asDouble(Double.NaN);
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            return handoff(turn, 0.0d, true, execution);
        }
        String route = text(root, "route");
        if ("HANDOFF".equals(route)) {
            String workflow = nullableText(root, "suggestedWorkflow");
            return new GatewayResult.Handoff(turn, Optional.ofNullable(workflow), confidence,
                    false, Optional.of(execution));
        }
        if ("REVIEW".equals(route) || confidence < configuration.getDirectConfidenceThreshold()) {
            return new GatewayResult.Review(turn, "policy_or_confidence_review",
                    Optional.of(execution));
        }
        if (!"DIRECT".equals(route)) return handoff(turn, confidence, true, execution);
        GatewayResult.DirectIntent intent;
        try {
            intent = GatewayResult.DirectIntent.valueOf(text(root, "intent"));
        } catch (RuntimeException unknownIntent) {
            return handoff(turn, confidence, true, execution);
        }
        String candidate = nullableText(root, "candidate");
        if (!StringUtils.hasText(candidate)) return handoff(turn, confidence, true, execution);
        return new GatewayResult.Direct(turn, intent, new AiMessage.Assistant(candidate), confidence,
                Optional.of(execution));
    }

    private GatewayResult.Handoff handoff(GatewayResult.GuardedTurn turn, double confidence,
                                           boolean fallback) {
        return new GatewayResult.Handoff(turn, Optional.empty(), confidence, fallback);
    }

    private GatewayResult.Handoff handoff(GatewayResult.GuardedTurn turn, double confidence,
                                           boolean fallback,
                                           GatewayResult.Execution execution) {
        return new GatewayResult.Handoff(turn, Optional.empty(), confidence, fallback,
                Optional.ofNullable(execution));
    }

    private String text(JsonNode root, String field) {
        String value = nullableText(root, field);
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Missing Gateway field: " + field);
        return value.toUpperCase(Locale.ROOT);
    }

    private String nullableText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText().strip();
    }

    private ExecutionScope executionScope(AgentWorkflowContext context) {
        return new ExecutionScope(context.request().requestId(),
                context.request().conversationId(), context.request().requesterId(), 0L,
                ExecutionScope.Purpose.GATEWAY_ROUTING,
                context.execution().guardrailDecisionIds());
    }

}
