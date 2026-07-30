package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Model-authored recursive Workflow graph. A member is exactly one Agent vertex
 * or one child Workflow graph, while edges define execution dependencies.
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

    public record WorkflowDefinition(String id, List<Member> members, List<Edge> edges) {

        /** Sequential convenience form: each member depends on its predecessor. */
        public WorkflowDefinition(String id, List<Member> members) {
            this(id, members, chainEdges(members));
        }

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
            edges = List.copyOf(Objects.requireNonNull(
                    edges, "Workflow dependency edges are required."));
            validateEdges(members, edges);
        }

        public List<String> predecessors(String memberId) {
            Set<String> predecessorIds = edges.stream()
                    .filter(edge -> edge.to().equals(memberId))
                    .map(Edge::from)
                    .collect(Collectors.toUnmodifiableSet());
            return members.stream().map(Member::id)
                    .filter(predecessorIds::contains).toList();
        }

        /** Members whose declared predecessors have all settled, in declaration order. */
        public List<Member> readyMembers(Set<String> pending, Set<String> completed) {
            Objects.requireNonNull(pending, "pending workflow member ids");
            Objects.requireNonNull(completed, "completed workflow member ids");
            return members.stream()
                    .filter(member -> pending.contains(member.id()))
                    .filter(member -> completed.containsAll(predecessors(member.id())))
                    .toList();
        }

        private static List<Edge> chainEdges(List<Member> members) {
            if (members == null || members.size() < 2) return List.of();
            ArrayList<Edge> chain = new ArrayList<>(members.size() - 1);
            for (int index = 1; index < members.size(); index++) {
                chain.add(new Edge(members.get(index - 1).id(), members.get(index).id()));
            }
            return List.copyOf(chain);
        }

        private static void validateEdges(List<Member> members, List<Edge> edges) {
            Set<String> ids = members.stream().map(Member::id)
                    .collect(Collectors.toUnmodifiableSet());
            Set<Edge> unique = new HashSet<>();
            Map<String, Integer> indegree = new HashMap<>();
            Map<String, List<String>> outgoing = new HashMap<>();
            ids.forEach(id -> {
                indegree.put(id, 0);
                outgoing.put(id, new ArrayList<>());
            });
            for (Edge edge : edges) {
                Objects.requireNonNull(edge, "Workflow edge");
                if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                    throw new IllegalArgumentException(
                            "Workflow edge references an unknown member: " + edge);
                }
                if (edge.from().equals(edge.to())) {
                    throw new IllegalArgumentException("A Workflow edge cannot reference itself.");
                }
                if (!unique.add(edge)) {
                    throw new IllegalArgumentException("Duplicate Workflow edge: " + edge);
                }
                outgoing.get(edge.from()).add(edge.to());
                indegree.compute(edge.to(), (ignored, value) -> value + 1);
            }
            ArrayDeque<String> ready = new ArrayDeque<>();
            indegree.forEach((member, count) -> {
                if (count == 0) ready.addLast(member);
            });
            int visited = 0;
            while (!ready.isEmpty()) {
                String member = ready.removeFirst();
                visited++;
                for (String target : outgoing.get(member)) {
                    int remaining = indegree.compute(target,
                            (ignored, value) -> value - 1);
                    if (remaining == 0) ready.addLast(target);
                }
            }
            if (visited != members.size()) {
                throw new IllegalArgumentException("Workflow dependency edges contain a cycle.");
            }
        }
    }

    /** Directed dependency: {@code to} may start only after {@code from} settles. */
    public record Edge(String from, String to) {
        public Edge {
            from = requiredId(from, "workflow edge source");
            to = requiredId(to, "workflow edge target");
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
                            String completedVerb, ToolAccess toolAccess,
                            Delegation delegation) {
        public AgentTask(String agentId, String label, String instruction,
                         String guideMessage, String activeVerb,
                         String completedVerb, ToolAccess toolAccess) {
            this(agentId, label, instruction, guideMessage, activeVerb,
                    completedVerb, toolAccess, Delegation.DIRECT);
        }

        public AgentTask {
            agentId = requiredId(agentId, "agent id");
            label = requiredText(label, "agent task label", 100);
            instruction = requiredText(instruction, "agent task instruction", 4_000);
            guideMessage = optionalText(guideMessage, "agent guideMessage", 180);
            activeVerb = optionalText(activeVerb, "agent activeVerb", 32);
            completedVerb = optionalText(completedVerb, "agent completedVerb", 32);
            toolAccess = toolAccess != null ? toolAccess : ToolAccess.NONE;
            delegation = delegation != null ? delegation : Delegation.DIRECT;
        }
    }

    /** Whether this task executes directly or owns another recursively planned Workflow. */
    public enum Delegation {
        DIRECT,
        FAN_OUT
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
