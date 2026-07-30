package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.DelegationIntent;
import org.oagi.score.gateway.http.api.ai_management.model.AiPersistentWorkflowCommand;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves per-turn delegation wording and persistent conversation workflow preferences. */
public final class AiWorkflowIntent {

    private static final Pattern PERSISTENT_SCOPE = Pattern.compile(
            "(?iu)(?:\\b(?:for\\s+)?(?:the\\s+)?(?:following|future|subsequent)\\s+"
                    + "(?:prompts?|requests?|questions?|turns?)\\b|\\bfrom\\s+now\\s+on\\b|"
                    + "\\bgoing\\s+forward\\b|\\balways\\b|\\bnever\\b)");
    private static final Pattern AUTOMATIC_WORKFLOW = Pattern.compile(
            "(?iu)\\b(?:automatic|automatically|auto|reset|default)\\b");
    private AiWorkflowIntent() {
    }

    public static ChatRequest applyExplicitDelegation(ChatRequest request) {
        if (request == null) {
            return request;
        }
        if (explicitlyRequestsAgents(request.prompt())) {
            AiMultiAgentOptions current = request.multiAgent();
            return request.withMultiAgent(
                            new AiMultiAgentOptions(true, AiMultiAgentOptions.MAX_AGENTS,
                                    current.strategy()))
                    .withActiveWorkflow("agents");
        }
        if (DelegationIntent.explicitlyNegatesAgents(request.prompt())
                && agentsAreActive(request)) {
            return request.withMultiAgent(AiMultiAgentOptions.single())
                    .withActiveWorkflow("assistant");
        }
        return request;
    }

    private static boolean agentsAreActive(ChatRequest request) {
        if (request.multiAgent().active()) return true;
        String workflow = request.activeWorkflow();
        return workflow != null && "agents".equalsIgnoreCase(workflow.strip());
    }

    public static Optional<AiPersistentWorkflowCommand> persistentWorkflowCommand(String prompt) {
        if (prompt == null || prompt.isBlank() || !PERSISTENT_SCOPE.matcher(prompt).find()) {
            return Optional.empty();
        }
        if (AUTOMATIC_WORKFLOW.matcher(prompt).find()
                && prompt.toLowerCase(Locale.ROOT).contains("workflow")) {
            return Optional.of(new AiPersistentWorkflowCommand(
                    null, AiPersistentWorkflowCommand.Mode.AUTOMATIC));
        }
        if (DelegationIntent.explicitlyNegatesAgents(prompt)) {
            return Optional.of(new AiPersistentWorkflowCommand(
                    "assistant", AiPersistentWorkflowCommand.Mode.DISABLE_AGENTS));
        }
        if (DelegationIntent.mentionsAgents(prompt)) {
            return Optional.of(new AiPersistentWorkflowCommand(
                    "agents", AiPersistentWorkflowCommand.Mode.ENABLE_AGENTS));
        }
        return Optional.empty();
    }

    static boolean explicitlyRequestsFanOut(String prompt) {
        return DelegationIntent.explicitlyRequestsFanOut(prompt);
    }

    public static boolean explicitlyRequestsAgents(String prompt) {
        return DelegationIntent.explicitlyRequestsAgents(prompt);
    }

}
