package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Sole translation from core Tools to Spring AI callbacks. */
@Component
public final class SpringAiToolAdapter {

    public ToolCallbackProvider adapt(ToolSet tools, ToolExecutionGateway gateway,
                                      ExecutionScope scope) {
        ToolCallback[] callbacks = (tools != null ? tools : ToolSet.empty()).values().stream()
                .map(tool -> callback(tool, gateway, scope))
                .toArray(ToolCallback[]::new);
        return () -> callbacks.clone();
    }

    private ToolCallback callback(AiTool tool, ToolExecutionGateway gateway, ExecutionScope scope) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(tool.specification().name())
                    .description(tool.specification().description())
                    .inputSchema(tool.specification().inputSchema())
                    .build();
            private final ToolMetadata metadata = ToolMetadata.builder().build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return metadata; }
            @Override public String call(String input) {
                return call(input, new ToolContext(java.util.Map.of()));
            }
            @Override public String call(String input, ToolContext context) {
                Objects.requireNonNull(gateway, "gateway");
                return gateway.execute(tool.specification().id(),
                        new AiTool.ToolArguments(input), scope).json();
            }
        };
    }
}
