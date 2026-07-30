package org.oagi.score.gateway.http.api.ai_management.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable set of concrete Tools visible to an Agent. */
public final class ToolSet {

    private static final ToolSet EMPTY = new ToolSet(List.of());
    private final Map<AiTool.ToolId, AiTool> tools;

    public ToolSet(Collection<? extends AiTool> tools) {
        Map<AiTool.ToolId, AiTool> indexed = new LinkedHashMap<>();
        if (tools != null) {
            for (AiTool tool : tools) {
                Objects.requireNonNull(tool, "tool");
                AiTool previous = indexed.put(tool.specification().id(), tool);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate tool id: " + tool.specification().id().value());
                }
            }
        }
        this.tools = Map.copyOf(indexed);
    }

    public static ToolSet empty() { return EMPTY; }
    public boolean isEmpty() { return tools.isEmpty(); }
    public int size() { return tools.size(); }
    public Collection<AiTool> values() { return tools.values(); }
    public Optional<AiTool> find(AiTool.ToolId id) { return Optional.ofNullable(tools.get(id)); }

    public ToolSet plus(ToolSet additional) {
        if (additional == null || additional.isEmpty()) return this;
        if (isEmpty()) return additional;
        java.util.ArrayList<AiTool> combined = new java.util.ArrayList<>(values());
        combined.addAll(additional.values());
        return new ToolSet(combined);
    }
}
