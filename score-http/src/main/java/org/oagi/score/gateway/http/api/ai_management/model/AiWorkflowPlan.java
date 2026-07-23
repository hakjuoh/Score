package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Model-authored recursive Workflow. A member is exactly one Agent call or one
 * child Workflow; execution order comes from the member queue, not a type name.
 */
public record AiWorkflowPlan(WorkflowDefinition root,
                             String guideMessage,
                             String synthesisGuideMessage) {

    public AiWorkflowPlan {
        Objects.requireNonNull(root, "root");
        guideMessage = optionalText(guideMessage, "guideMessage", 180);
        synthesisGuideMessage = optionalText(
                synthesisGuideMessage, "synthesisGuideMessage", 180);
    }

    public record WorkflowDefinition(String id, List<Member> members) {
        public WorkflowDefinition {
            id = requiredId(id, "workflow id");
            members = members != null ? List.copyOf(members) : List.of();
            if (members.isEmpty()) {
                throw new IllegalArgumentException("A Workflow requires at least one member.");
            }
            Set<String> memberIds = new HashSet<>();
            for (Member member : members) {
                Objects.requireNonNull(member, "Workflow member");
                if (!memberIds.add(member.id())) {
                    throw new IllegalArgumentException(
                            "Duplicate Workflow member id: " + member.id());
                }
            }
        }
    }

    public record Member(String id, AgentTask agent, WorkflowDefinition workflow) {
        public Member {
            id = requiredId(id, "workflow member id");
            if ((agent == null) == (workflow == null)) {
                throw new IllegalArgumentException(
                        "A Workflow member must contain exactly one Agent or child Workflow.");
            }
        }
    }

    public record AgentTask(String agentId, String label, String instruction,
                            String guideMessage, String activeVerb,
                            String completedVerb, ToolAccess toolAccess) {
        public AgentTask {
            agentId = requiredId(agentId, "agent id");
            label = requiredText(label, "agent task label", 100);
            instruction = requiredText(instruction, "agent task instruction", 4_000);
            guideMessage = optionalText(guideMessage, "agent guideMessage", 180);
            activeVerb = optionalText(activeVerb, "agent activeVerb", 32);
            completedVerb = optionalText(completedVerb, "agent completedVerb", 32);
            toolAccess = toolAccess != null ? toolAccess : ToolAccess.NONE;
        }
    }

    /** Per-call Tool authority. Agent definitions never grant their own authority. */
    public enum ToolAccess {
        NONE,
        READ_ONLY,
        FULL
    }

    private static String requiredId(String value, String label) {
        String normalized = requiredText(value, label, 100);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return normalized;
    }

    private static String requiredText(String value, String label, int maximumLength) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(label + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String optionalText(String value, String label, int maximumLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(label + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
