package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves per-turn delegation wording and persistent conversation workflow preferences. */
final class AiMultiAgentIntent {

    private static final Pattern AGENT_TERM = Pattern.compile(
            "(?iu)(?:\\bsub[\\s-]?agents?\\b|\\bagents?\\b)");
    private static final Pattern DELEGATION_TERM = Pattern.compile(
            "(?iu)(?:\\bfan[\\s-]?out\\b|\\bspawn\\b|\\bdelegate\\b|\\bin parallel\\b|"
                    + "\\bsplit up\\b)");
    private static final Pattern AGENT_USAGE_TERM = Pattern.compile(
            "(?iu)\\b(?:use|using|with|via)\\s+(?:sub[\\s-]?)?agents?\\b");
    private static final Pattern NEGATED_DELEGATION = Pattern.compile(
            "(?iu)(?:(?:do not|don't|never|without|no|stop)\\s+(?:using\\s+|use\\s+)?(?:sub[\\s-]?)?agents?"
                    + "|(?:sub[\\s-]?agents?|agents?).{0,24}(?:forbidden|disabled|never)"
                    + "|(?:sub[\\s-]?)?agents?.{0,12}(?:쓰지\\s*마|사용하지\\s*마|금지))");
    private static final Pattern PERSISTENT_SCOPE = Pattern.compile(
            "(?iu)(?:\\b(?:for\\s+)?(?:the\\s+)?(?:following|future|subsequent)\\s+"
                    + "(?:prompts?|requests?|questions?|turns?)\\b|\\bfrom\\s+now\\s+on\\b|"
                    + "\\bgoing\\s+forward\\b|\\balways\\b|\\bnever\\b|앞으로|다음부터|"
                    + "이후(?:에는|부터)?|계속|항상|절대)");
    private static final Pattern AUTOMATIC_WORKFLOW = Pattern.compile(
            "(?iu)(?:\\b(?:automatic|automatically|auto|reset|default)\\b|자동(?:으로| 선택)?|초기화)");
    private static final Pattern WORKFLOW_TERM = Pattern.compile(
            "(?iu)\\b(direct|chain|parallel|routing|orchestrator(?:[\\s_-]+workers?)?)\\b");
    private static final Pattern NUMERIC_AGENT_COUNT = Pattern.compile(
            "(?iu)([2-4])\\s*(?:sub[\\s-]?)?agents?");
    private static final Pattern WORD_AGENT_COUNT = Pattern.compile(
            "(?iu)\\b(two|three|four)\\s+(?:sub[\\s-]?)?agents?\\b");
    private static final Pattern ENGLISH_COMPARISON = Pattern.compile(
            "(?iu)\\bcompare\\s+(.{2,80}?)\\s+(?:and|with|versus|vs\\.?)\\s+"
                    + "(.{2,80}?)(?=\\s+(?:in|for|from|on)\\b|[,.!?]|$)");

    private AiMultiAgentIntent() {
    }

    static ChatRequest applyExplicitDelegation(ChatRequest request) {
        if (request == null || request.activeWorkflow() != null
                || !explicitlyRequestsAgents(request.prompt())) {
            return request;
        }
        int count = requestedAgentCount(request.prompt()).orElse(2);
        AiMultiAgentOptions current = request.multiAgent();
        String workflow = explicitlyRequestsFanOut(request.prompt())
                ? "parallel" : "orchestrator_workers";
        return request.withMultiAgent(new AiMultiAgentOptions(true, count, current.strategy()))
                .withActiveWorkflow(workflow);
    }

    static Optional<PersistentWorkflowCommand> persistentWorkflowCommand(String prompt) {
        if (prompt == null || prompt.isBlank() || !PERSISTENT_SCOPE.matcher(prompt).find()) {
            return Optional.empty();
        }
        if (AUTOMATIC_WORKFLOW.matcher(prompt).find()
                && (prompt.toLowerCase(Locale.ROOT).contains("workflow")
                || prompt.contains("워크플로"))) {
            return Optional.of(new PersistentWorkflowCommand(null, Mode.AUTOMATIC));
        }
        if (NEGATED_DELEGATION.matcher(prompt).find()) {
            return Optional.of(new PersistentWorkflowCommand("direct", Mode.DISABLE_AGENTS));
        }
        Matcher named = WORKFLOW_TERM.matcher(prompt);
        if (named.find()) {
            String workflow = named.group(1).toLowerCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            if (workflow.startsWith("orchestrator")) workflow = "orchestrator_workers";
            return Optional.of(new PersistentWorkflowCommand(workflow, Mode.NAMED));
        }
        if (AGENT_TERM.matcher(prompt).find()) {
            String workflow = explicitlyRequestsFanOut(prompt)
                    ? "parallel" : "orchestrator_workers";
            return Optional.of(new PersistentWorkflowCommand(workflow, Mode.ENABLE_AGENTS));
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

    /**
     * Extracts the two explicitly compared subjects when the wording is clear enough to use
     * as presentation-only task labels. Ambiguous prompts deliberately keep generic role labels.
     */
    static List<String> comparisonWorkItems(String prompt) {
        if (prompt == null || prompt.isBlank()) return List.of();
        Matcher english = ENGLISH_COMPARISON.matcher(normalize(prompt));
        if (english.find()) {
            return boundedDistinct(english.group(1), english.group(2));
        }
        return List.of();
    }

    private static List<String> boundedDistinct(String first, String second) {
        List<String> labels = new ArrayList<>(2);
        String left = cleanLabel(first);
        String right = cleanLabel(second);
        if (!left.isBlank()) labels.add(left);
        if (!right.isBlank() && !right.equalsIgnoreCase(left)) labels.add(right);
        return labels.size() == 2 ? List.copyOf(labels) : List.of();
    }

    private static String cleanLabel(String value) {
        String label = normalize(value)
                .replaceFirst("(?iu)^(?:please\\s+)?(?:compare|review|inspect|check)\\s+", "")
                .replaceFirst("^[>•*\\-]+\\s*", "")
                .replaceFirst("(?iu)\\s+(?:please|independently)$", "")
                .strip();
        return label.length() <= 80 ? label : label.substring(0, 77).stripTrailing() + "...";
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    record PersistentWorkflowCommand(String activeWorkflow, Mode mode) {

        String acknowledgement() {
            return switch (mode) {
                case ENABLE_AGENTS -> "Understood. I’ll use sub-agents for subsequent requests "
                        + "in this conversation. What would you like to know?";
                case DISABLE_AGENTS -> "Understood. I won’t use sub-agents for subsequent requests "
                        + "in this conversation. What would you like to know?";
                case AUTOMATIC -> "Understood. I’ll choose the workflow separately for each subsequent "
                        + "request in this conversation. What would you like to know?";
                case NAMED -> "Understood. I’ll use the " + activeWorkflow
                        + " workflow for subsequent requests in this conversation. What would you like to know?";
            };
        }
    }

    enum Mode {
        ENABLE_AGENTS, DISABLE_AGENTS, AUTOMATIC, NAMED
    }
}
