package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Definition for the low-latency no-Tool routing Agent. */
@Component("gateway-agent")
public final class GatewayAgent implements Agent {

    private final AgentDefinition definition;
    private final ObjectMapper objectMapper;
    private final ScoreAiProperties.Gateway configuration;

    public GatewayAgent(AiAgentCatalog agents, ObjectMapper objectMapper,
                        ScoreAiProperties properties) {
        this(agents, objectMapper, properties, null, ScoreAiObservability.noop());
    }

    @Autowired
    public GatewayAgent(AiAgentCatalog agents, ObjectMapper objectMapper,
                        ScoreAiProperties properties,
                        AgentOutputGuardrailChain outputGuardrails,
                        ScoreAiObservability observability) {
        this.objectMapper = objectMapper;
        this.configuration = properties.getGateway();
        AgentDefinition configured = agents.systemDefinition("gateway-agent");
        this.definition = new AgentDefinition(configured.id(), configured.name(),
                configured.description(), configured.instruction(), this::prepare,
                AgentToolHandler.none(), responses(), AgentGuardrailHandlers.publicOutput(
                        outputGuardrails, observability, "gateway_output",
                        Map.of("agent_id", "gateway-agent")), false);
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    public boolean enabled() {
        return configuration.isEnabled();
    }

    private AgentRunRequest prepare(Agent agent, AgentWorkflowContext context) {
        if (context.request().changeConfirmation()
                || !enabled()
                || context.request().hasAttachments()
                || context.request().prompt().length() > configuration.getMaximumInputCharacters()
                || context.request().delegationRequested()
                || context.request().explicitDelegationRequested()
                || DelegationIntent.explicitlyNegatesAgents(context.request().prompt())) {
            return new AgentRunRequest.Skip(resolveHandoff(context, null));
        }
        ExecutionScope scope = context.executionScope(ExecutionScope.Purpose.GATEWAY_ROUTING);
        AiMessage.User input = context.execution().userMessage();
        return new AgentRunRequest.Model(context.request().modelName(),
                definition.instruction().render(), input, List.of(), scope,
                context.observationContext());
    }

    private AgentResponseHandler responses() {
        return new AgentResponseHandler() {
            @Override
            public AgentDecision handle(AgentResponseContext response) {
                JsonNode root = parse(response.result().response().content());
                String policy = text(root, "policyAction");
                if ("REFUSE".equals(policy)) {
                    GuardrailDecision decision = GuardrailDecision.of("gateway-turn-policy", "1",
                            GuardrailDecision.Action.REFUSE);
                    throw new AgentGuardrailRefusedException(new GuardrailRefusal(decision,
                            "GATEWAY_POLICY_REFUSAL", "ai.policy.refused"),
                            response.agent().id());
                }
                if (!"ALLOW".equals(policy)) return resolveHandoff(response.workflow(), null);
                double confidence = root.path("confidence").asDouble(Double.NaN);
                if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d
                        || confidence < configuration.getDirectConfidenceThreshold()) {
                    return resolveHandoff(response.workflow(), text(root, "suggestedWorkflow"));
                }
                String route = text(root, "route");
                if (!"DIRECT".equals(route)) return resolveHandoff(response.workflow(), text(root, "suggestedWorkflow"));
                String intent = text(root, "intent");
                if (!"THANKS".equals(intent)) return resolveHandoff(response.workflow(), text(root, "suggestedWorkflow"));
                String candidate = nullableText(root, "candidate");
                if (!StringUtils.hasText(candidate)) return resolveHandoff(response.workflow(), text(root, "suggestedWorkflow"));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("gateway", true);
                metadata.put("intent", intent);
                metadata.put("confidence", confidence);
                metadata.put("executionPurpose", ExecutionScope.Purpose.GATEWAY_ROUTING.name());
                metadata.putAll(response.result().metadata().attributes());
                return new AgentDecision.Complete(new AgentOutput(
                        candidate, Map.copyOf(metadata)));
            }

            @Override
            public AgentDecision onFailure(AgentFailure failure) {
                if (failure.exception() instanceof CancellationException
                        || failure.exception() instanceof AgentGuardrailRefusedException) {
                    throw failure.exception();
                }
                return resolveHandoff(failure.workflow(), null);
            }
        };
    }

    private AgentDecision resolveHandoff(AgentWorkflowContext context, String suggestedWorkflow) {
        if (context == null || context.request().maximumAgents() <= 1) {
            return new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID);
        }
        if (DelegationIntent.explicitlyNegatesAgents(context.request().prompt())) {
            return new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID);
        }
        if (context.request().delegationRequested()
                || context.request().explicitDelegationRequested()) {
            return new AgentDecision.Handoff(PlannerAgent.PLANNER_ID);
        }
        if ("AGENTS".equalsIgnoreCase(suggestedWorkflow)) {
            return new AgentDecision.Handoff(PlannerAgent.PLANNER_ID);
        }
        return new AgentDecision.Handoff(AssistantAgent.ASSISTANT_ID);
    }

    private JsonNode parse(String raw) {
        if (!StringUtils.hasText(raw)) return objectMapper.createObjectNode();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception malformed) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode root, String field) {
        String value = nullableText(root, field);
        return StringUtils.hasText(value) ? value.toUpperCase(Locale.ROOT) : "";
    }

    private String nullableText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() || !value.isTextual()
                ? null : value.asText().strip();
    }
}
