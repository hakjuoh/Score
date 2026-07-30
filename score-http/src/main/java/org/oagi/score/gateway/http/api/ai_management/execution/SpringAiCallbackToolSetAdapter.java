package org.oagi.score.gateway.http.api.ai_management.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Transitional provider adapter from Spring MCP callbacks into protocol-neutral core Tools. */
@Component
public final class SpringAiCallbackToolSetAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ToolSet adapt(ToolCallbackProvider provider, Set<String> readOnlyToolNames) {
        return adapt(provider, readOnlyToolNames, List.of());
    }

    public ToolSet adapt(ToolCallbackProvider provider, Set<String> readOnlyToolNames,
                         List<McpSchema.Tool> mcpCatalog) {
        ToolCallback[] callbacks = provider != null ? provider.getToolCallbacks() : new ToolCallback[0];
        Set<String> readOnly = readOnlyToolNames != null ? Set.copyOf(readOnlyToolNames) : Set.of();
        Map<String, McpSchema.Tool> metadata = mcpCatalog != null
                ? mcpCatalog.stream().collect(Collectors.toUnmodifiableMap(
                        McpSchema.Tool::name, Function.identity())) : Map.of();
        return new ToolSet(Arrays.stream(callbacks)
                .map(callback -> tool(callback, readOnly, metadata.get(
                        callback.getToolDefinition().name())))
                .toList());
    }

    private AiTool tool(ToolCallback callback, Set<String> readOnly, McpSchema.Tool mcpTool) {
        var definition = callback.getToolDefinition();
        AiTool.ToolEffect effect = readOnly.contains(definition.name())
                ? AiTool.ToolEffect.READ_ONLY : AiTool.ToolEffect.CHANGE;
        AiTool.ToolSpecification specification = new AiTool.ToolSpecification(
                new AiTool.ToolId(definition.name()), definition.name(),
                mcpTool != null ? mcpTool.description() : definition.description(),
                mcpTool != null ? json(mcpTool.inputSchema()) : definition.inputSchema(),
                mcpTool != null ? json(mcpTool.outputSchema()) : "{}", effect);
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

    private String json(Map<String, Object> schema) {
        if (schema == null) return "{}";
        try {
            return JSON.writeValueAsString(schema);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP Tool schema could not be serialized.", failure);
        }
    }
}
