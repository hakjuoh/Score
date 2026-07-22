package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Transitional provider adapter from Spring MCP callbacks into protocol-neutral core Tools. */
@Component
public final class SpringAiCallbackToolSetAdapter {

    public ToolSet adapt(ToolCallbackProvider provider, Set<String> readOnlyToolNames) {
        ToolCallback[] callbacks = provider != null ? provider.getToolCallbacks() : new ToolCallback[0];
        Set<String> readOnly = readOnlyToolNames != null ? Set.copyOf(readOnlyToolNames) : Set.of();
        return new ToolSet(Arrays.stream(callbacks).map(callback -> tool(callback, readOnly)).toList());
    }

    private AiTool tool(ToolCallback callback, Set<String> readOnly) {
        var definition = callback.getToolDefinition();
        AiTool.ToolEffect effect = readOnly.contains(definition.name())
                ? AiTool.ToolEffect.READ_ONLY : AiTool.ToolEffect.MUTATION;
        AiTool.ToolSpecification specification = new AiTool.ToolSpecification(
                new AiTool.ToolId(definition.name()), definition.name(), definition.description(),
                definition.inputSchema(), "{}", effect);
        return new AiTool() {
            @Override public ToolSpecification specification() { return specification; }
            @Override public ToolResult execute(ToolArguments arguments, ToolExecutionContext context) {
                String result = callback.call(arguments.json(), new ToolContext(Map.of(
                        "request_id", context.scope().requestId(),
                        "conversation_id", context.scope().conversationId())));
                return new ToolResult(result);
            }
        };
    }
}
