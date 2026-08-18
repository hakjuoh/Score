package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.oagi.score.gateway.http.api.ai_management.agent.*;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Generates a concise conversation title using the configured lightweight model or default model. */
@Component
public class ConversationTitleGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationTitleGenerator.class);
    private static final int MAX_TITLE_LENGTH = 240;

    private final AgentRunner runner;
    private final ScoreAiModelRegistry models;
    private final AgentDefinition baseDefinition;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final ScoreAiObservability observability;

    @Autowired
    public ConversationTitleGenerator(AgentRunner runner,
                                      ScoreAiModelRegistry models,
                                      AiAgentCatalog catalog,
                                      AgentOutputGuardrailChain outputGuardrails,
                                      ScoreAiObservability observability) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.models = Objects.requireNonNull(models, "models");
        this.baseDefinition = catalog.systemDefinition("conversation-titler");
        this.outputGuardrails = outputGuardrails;
        this.observability = observability != null ? observability : ScoreAiObservability.noop();
    }

    ConversationTitleGenerator(AgentRunner runner,
                               ScoreAiModelRegistry models,
                               AiAgentCatalog catalog) {
        this(runner, models, catalog, null, ScoreAiObservability.noop());
    }

    public String generateTitle(String prompt, ExecutionScope parentScope) {
        if (!StringUtils.hasText(prompt)) {
            return "New conversation";
        }
        try {
            String modelId = models.lightweightModelName();
            ExecutionScope scope = parentScope != null
                    ? parentScope.withPurpose(ExecutionScope.Purpose.CONVERSATION_TITLING)
                    : new ExecutionScope("title-" + java.util.UUID.randomUUID(),
                            "title-gen", "system", 0L,
                            ExecutionScope.Purpose.CONVERSATION_TITLING, List.of());

            AiMessage.User userMessage = new AiMessage.User(prompt.strip());

            ChatExecutionContext execution = ChatExecutionContext.standalone(
                    scope.requestId(), scope.conversationId(), baseDefinition.id().value(), modelId,
                    userMessage, null, scope.purpose());
            AgentWorkflowContext workflow = AgentWorkflowContext.root(execution,
                    new AgentWorkflowContext.Request(scope.requestId(), scope.conversationId(),
                            scope.requesterId(), modelId, userMessage.content(), false, false,
                            1, "balanced", null, false, false, false), 1);

            Agent agent = new DefinedAgent(new AgentDefinition(baseDefinition.id(),
                    baseDefinition.name(), baseDefinition.description(), baseDefinition.instruction(),
                    requestHandler(modelId, userMessage, scope),
                    (ignored, context) -> new org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding(
                            org.oagi.score.gateway.http.api.ai_management.tool.ToolSet.empty(),
                            ToolExecutionGateway.disabled()),
                    AgentResponseHandler.complete(),
                    guardrails(AgentOutputGuardrail.Scope.INTERNAL), false));

            AgentDecision decision = runner.run(agent, workflow);
            if (decision instanceof AgentDecision.Complete complete) {
                String rawTitle = complete.result().content();
                return sanitizeTitle(rawTitle, prompt);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to generate title with lightweight model, falling back to prompt.", e);
        }
        return sanitizeTitle(prompt, prompt);
    }

    public String generateTitle(String prompt, String answer, ExecutionScope parentScope) {
        return generateTitle(prompt, parentScope);
    }

    private AgentRequestHandler requestHandler(String modelId, AiMessage.User request, ExecutionScope scope) {
        return (agent, context) -> new AgentRunRequest.Model(modelId,
                baseDefinition.instruction().render(), request, List.of(), scope,
                Map.of("feature", "titling"));
    }

    private AgentGuardrails guardrails(AgentOutputGuardrail.Scope scope) {
        if (outputGuardrails == null) {
            return AgentGuardrails.none();
        }
        return new AgentGuardrails(List.of(), List.of(AgentGuardrailHandlers.output(
                outputGuardrails, observability, scope,
                "titling_output", Map.of("feature", "titling"))), scope);
    }

    public static String sanitizeTitle(String generated, String fallback) {
        String value = StringUtils.hasText(generated) ? generated.strip() : fallback;
        if (!StringUtils.hasText(value)) return "New conversation";
        if (value.regionMatches(true, 0, "Title:", 0, 6)) {
            value = value.substring(6).strip();
        }
        value = value.replaceAll("^[\"'`]+", "")
                .replaceAll("[\"'`]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (value.regionMatches(true, 0, "Title:", 0, 6)) {
            value = value.substring(6).strip();
        }
        value = value.replaceAll("^[\"'`]+", "")
                .replaceAll("[\"'`]+$", "")
                .strip();
        if (value.isEmpty()) value = StringUtils.hasText(fallback) ? fallback.strip() : "New conversation";
        if (value.length() > MAX_TITLE_LENGTH) {
            return value.substring(0, MAX_TITLE_LENGTH - 3) + "...";
        }
        return value;
    }
}
