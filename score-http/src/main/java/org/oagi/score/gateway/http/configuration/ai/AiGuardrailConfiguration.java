package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolInputNormalizer;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** Required deterministic Guardrail baseline. Deployments may append stricter registrations. */
@Configuration(proxyBeanMethods = false)
public class AiGuardrailConfiguration {

    @Bean
    public AgentInputGuardrailChain agentInputGuardrailChain() {
        AgentInputGuardrail baseline = request -> {
            String original = request.input().content();
            String safe = AiSensitiveDataRedactor.redactText(original);
            if (!safe.equals(original)) {
                return new AgentInputGuardrail.Result.Rewrite(
                        new AiMessage.User(safe, request.input().attachments()),
                        GuardrailDecision.of("baseline-secret-input", "1",
                                GuardrailDecision.Action.REWRITE));
            }
            return new AgentInputGuardrail.Result.Allow(
                    GuardrailDecision.of("baseline-secret-input", "1",
                            GuardrailDecision.Action.ALLOW));
        };
        return new AgentInputGuardrailChain(List.of(baseline));
    }

    @Bean
    public AgentOutputGuardrailChain agentOutputGuardrailChain() {
        AgentOutputGuardrail secretRedaction = request -> {
            String original = request.candidate().content();
            String safe = AiSensitiveDataRedactor.redactText(original);
            if (safe.equals(original)) {
                return new AgentOutputGuardrail.Result.Allow(request.candidate(),
                        GuardrailDecision.of("baseline-secret-output", "1",
                                GuardrailDecision.Action.ALLOW));
            }
            return new AgentOutputGuardrail.Result.Rewrite(new AiMessage.Assistant(safe),
                    GuardrailDecision.of("baseline-secret-output", "1",
                            GuardrailDecision.Action.REWRITE));
        };
        return new AgentOutputGuardrailChain(List.of(secretRedaction));
    }

    @Bean
    public ToolGuardrailRegistry toolGuardrailRegistry(ObjectMapper objectMapper) {
        AiToolInputNormalizer normalizer = new AiToolInputNormalizer(objectMapper);
        ToolInputGuardrail input = request -> {
            String normalized = normalizer.normalize(request.arguments().json(),
                    request.tool().inputSchema());
            if (!normalized.equals(request.arguments().json())) {
                return new ToolInputGuardrail.Result.Rewrite(
                        new AiTool.ToolArguments(normalized),
                        GuardrailDecision.of("baseline-tool-input-normalization", "1",
                                GuardrailDecision.Action.REWRITE));
            }
            return new ToolInputGuardrail.Result.Allow(request.arguments(),
                    GuardrailDecision.of("baseline-tool-input-normalization", "1",
                            GuardrailDecision.Action.ALLOW));
        };
        ToolOutputGuardrail output = request -> {
            String safe = AiSensitiveDataRedactor.redactText(request.output().json());
            if (safe.equals(request.output().json())) {
                return new ToolOutputGuardrail.Result.Allow(request.output(),
                        GuardrailDecision.of("baseline-tool-output", "1",
                                GuardrailDecision.Action.ALLOW));
            }
            return new ToolOutputGuardrail.Result.Rewrite(
                    new AiTool.ToolResult(safe, request.output().metadata()),
                    GuardrailDecision.of("baseline-tool-output", "1",
                            GuardrailDecision.Action.REWRITE));
        };
        return new ToolGuardrailRegistry(new ToolGuardrailRegistry.Set(
                List.of(input), List.of(output)), Map.of());
    }
}
