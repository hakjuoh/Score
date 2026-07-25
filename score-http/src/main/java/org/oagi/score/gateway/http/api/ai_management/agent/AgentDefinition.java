package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable Agent definition.
 *
 * <p>The definition contains both sides of a model turn: preparation and
 * resource policy before the call, and interpretation and policy after it.
 * The shared {@code AgentRunner} owns the loop and invokes these handlers; an
 * Agent definition never executes itself.</p>
 */
public record AgentDefinition(Agent.AgentId id, String name, String description,
                              InstructionTemplate instruction,
                              AgentRequestHandler requestHandler,
                              AgentToolHandler toolHandler,
                              AgentResponseHandler responseHandler,
                              AgentGuardrails guardrails,
                              boolean assignable) {

    public AgentDefinition(Agent.AgentId id, String name, String description,
                           InstructionTemplate instruction) {
        this(id, name, description, instruction,
                AgentRequestHandler.defaultRequest(), AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), false);
    }

    public AgentDefinition(Agent.AgentId id, String name, String description,
                           InstructionTemplate instruction,
                           AgentRequestHandler requestHandler,
                           AgentToolHandler toolHandler,
                           AgentResponseHandler responseHandler,
                           AgentGuardrails guardrails) {
        this(id, name, description, instruction, requestHandler, toolHandler,
                responseHandler, guardrails, false);
    }

    public AgentDefinition(Agent.AgentId id, String name, String description,
                           InstructionTemplate instruction, boolean assignable) {
        this(id, name, description, instruction,
                AgentRequestHandler.defaultRequest(), AgentToolHandler.none(),
                AgentResponseHandler.complete(), AgentGuardrails.none(), assignable);
    }

    public AgentDefinition {
        Objects.requireNonNull(id, "id");
        name = requireText(name, "name");
        description = requireText(description, "description");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(requestHandler, "requestHandler");
        Objects.requireNonNull(toolHandler, "toolHandler");
        Objects.requireNonNull(responseHandler, "responseHandler");
        Objects.requireNonNull(guardrails, "guardrails");
    }

    public record InstructionTemplate(String value) {

        private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)}");

        public InstructionTemplate {
            value = requireText(value, "instruction template");
        }

        public Agent.Instruction render() {
            return new Agent.Instruction(value);
        }

        public Agent.Instruction render(Map<String, ?> parameters) {
            Objects.requireNonNull(parameters, "instruction parameters");
            Matcher matcher = PLACEHOLDER.matcher(value);
            StringBuilder rendered = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                Object replacement = parameters.get(name);
                if (replacement == null) {
                    throw new IllegalArgumentException("Missing Agent instruction parameter: " + name);
                }
                matcher.appendReplacement(rendered,
                        Matcher.quoteReplacement(replacement.toString()));
            }
            matcher.appendTail(rendered);
            return new Agent.Instruction(rendered.toString());
        }

        public Agent.Instruction renderStrict() {
            return renderStrict(Map.of());
        }

        /** Renders a closed template contract, rejecting missing and unused parameters. */
        public Agent.Instruction renderStrict(Map<String, ?> parameters) {
            Objects.requireNonNull(parameters, "instruction parameters");
            Set<String> expected = placeholderNames();
            Set<String> supplied = new TreeSet<>(parameters.keySet());
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(supplied);
            Set<String> unexpected = new TreeSet<>(supplied);
            unexpected.removeAll(expected);
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid Agent instruction parameters; missing=" + missing
                                + ", unexpected=" + unexpected);
            }
            return render(parameters);
        }

        private Set<String> placeholderNames() {
            Set<String> names = new TreeSet<>();
            Matcher matcher = PLACEHOLDER.matcher(value);
            while (matcher.find()) names.add(matcher.group(1));
            return names;
        }
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Agent " + label + " is required.");
        return normalized;
    }
}
