package org.oagi.score.gateway.http.api.ai_management.tool;

/** Agent-level Tool visibility, independent of change confirmation. */
public enum ToolSetPolicy {
    NONE,
    READ_ONLY,
    FULL;

    public ToolSet filter(ToolSet available) {
        if (available == null || this == NONE) return ToolSet.empty();
        if (this == FULL) return available;
        return new ToolSet(available.values().stream()
                .filter(tool -> tool.specification().effect() == AiTool.ToolEffect.READ_ONLY)
                .toList());
    }
}
