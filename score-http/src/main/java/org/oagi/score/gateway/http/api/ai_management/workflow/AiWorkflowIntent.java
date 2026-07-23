package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiPersistentWorkflowCommand;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves per-turn delegation wording and persistent conversation workflow preferences. */
public final class AiWorkflowIntent {

    private static final Pattern AGENT_TERM = Pattern.compile(
            "(?iu)(?:\\bsub[\\s-]?agents?\\b|\\bagents?\\b)");
    private static final Pattern DELEGATION_TERM = Pattern.compile(
            "(?iu)(?:\\bfan[\\s-]?out\\b|\\bspawn\\b|\\bdelegate\\b|\\bin parallel\\b|"
                    + "\\bsplit up\\b)");
    private static final Pattern AGENT_USAGE_TERM = Pattern.compile(
            "(?iu)\\b(?:use|using|with|via)\\s+(?:sub[\\s-]?)?agents?\\b");
    private static final Pattern NEGATED_DELEGATION = Pattern.compile(
            "(?iu)(?:(?:do not|don't|never|without|no|stop)\\s+(?:using\\s+|use\\s+)?(?:sub[\\s-]?)?agents?"
                    + "|(?:sub[\\s-]?agents?|agents?).{0,24}(?:forbidden|disabled|never))");
    private static final Pattern PERSISTENT_SCOPE = Pattern.compile(
            "(?iu)(?:\\b(?:for\\s+)?(?:the\\s+)?(?:following|future|subsequent)\\s+"
                    + "(?:prompts?|requests?|questions?|turns?)\\b|\\bfrom\\s+now\\s+on\\b|"
                    + "\\bgoing\\s+forward\\b|\\balways\\b|\\bnever\\b)");
    private static final Pattern AUTOMATIC_WORKFLOW = Pattern.compile(
            "(?iu)\\b(?:automatic|automatically|auto|reset|default)\\b");
    private static final Pattern NUMERIC_AGENT_COUNT = Pattern.compile(
            "(?iu)([2-4])\\s*(?:sub[\\s-]?)?agents?");
    private static final Pattern WORD_AGENT_COUNT = Pattern.compile(
            "(?iu)\\b(two|three|four)\\s+(?:sub[\\s-]?)?agents?\\b");

    private AiWorkflowIntent() {
    }

    public static ChatRequest applyExplicitDelegation(ChatRequest request) {
        if (request == null || request.activeWorkflow() != null
                || !explicitlyRequestsAgents(request.prompt())) {
            return request;
        }
        int count = requestedAgentCount(request.prompt()).orElse(2);
        AiMultiAgentOptions current = request.multiAgent();
        return request.withMultiAgent(new AiMultiAgentOptions(true, count, current.strategy()))
                .withActiveWorkflow("agents");
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
        if (NEGATED_DELEGATION.matcher(prompt).find()) {
            return Optional.of(new AiPersistentWorkflowCommand(
                    "assistant", AiPersistentWorkflowCommand.Mode.DISABLE_AGENTS));
        }
        if (AGENT_TERM.matcher(prompt).find()) {
            return Optional.of(new AiPersistentWorkflowCommand(
                    "agents", AiPersistentWorkflowCommand.Mode.ENABLE_AGENTS));
        }
        return Optional.empty();
    }

    static boolean explicitlyRequestsFanOut(String prompt) {
        if (prompt == null || prompt.isBlank() || NEGATED_DELEGATION.matcher(prompt).find()) {
            return false;
        }
        return AGENT_TERM.matcher(prompt).find() && DELEGATION_TERM.matcher(prompt).find();
    }

    static boolean explicitlyRequestsAgents(String prompt) {
        if (prompt == null || prompt.isBlank() || NEGATED_DELEGATION.matcher(prompt).find()) {
            return false;
        }
        return explicitlyRequestsFanOut(prompt) || AGENT_USAGE_TERM.matcher(prompt).find();
    }

    static Optional<Integer> requestedAgentCount(String prompt) {
        if (prompt == null || prompt.isBlank()) return Optional.empty();
        Matcher numeric = NUMERIC_AGENT_COUNT.matcher(prompt);
        if (numeric.find()) {
            return Optional.of(Integer.parseInt(numeric.group(1)));
        }
        Matcher word = WORD_AGENT_COUNT.matcher(prompt);
        if (!word.find()) return Optional.empty();
        return Optional.of(switch (word.group(1).toLowerCase(Locale.ROOT)) {
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            default -> throw new IllegalStateException("Unsupported bounded agent count.");
        });
    }

}
