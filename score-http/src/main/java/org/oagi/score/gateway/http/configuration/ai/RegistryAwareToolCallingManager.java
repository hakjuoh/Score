package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps execution resolution separate from the smaller set of tool definitions exposed
 * to the model by tool search.
 *
 * <p>A model can legitimately call an exact tool name that is already present in the
 * conversation (for example, an approved change or an explicit read-back request).
 * Spring AI's tool-search advisor otherwise resolves that call only against the tools
 * selected by a preceding search iteration. This manager falls back to the full,
 * request-scoped callback registry without exposing every tool definition to the model.</p>
 */
final class RegistryAwareToolCallingManager implements ToolCallingManager {

    static final String TOOL_CALLBACK_REGISTRY_CONTEXT_KEY =
            RegistryAwareToolCallingManager.class.getName() + ".toolCallbackRegistry";

    private final ToolCallingManager delegate;

    RegistryAwareToolCallingManager() {
        this(ToolCallingManager.builder().build());
    }

    RegistryAwareToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        Map<String, ToolCallback> registry = callbackRegistry(options.getToolContext());
        if (registry.isEmpty() || chatResponse == null || !chatResponse.hasToolCalls()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        Map<String, ToolCallback> executable = new LinkedHashMap<>();
        if (options.getToolCallbacks() != null) {
            options.getToolCallbacks().forEach(callback -> executable.put(
                    callback.getToolDefinition().name(), callback));
        }
        chatResponse.getResults().stream()
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .map(call -> call.name())
                .distinct()
                .map(registry::get)
                .filter(java.util.Objects::nonNull)
                .forEach(callback -> executable.putIfAbsent(
                        callback.getToolDefinition().name(), callback));
        chatResponse.getResults().stream()
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .map(call -> call.name())
                .filter(name -> !executable.containsKey(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException(
                            "No ToolCallback found for tool name: " + name);
                });

        Map<String, Object> callbackContext = new HashMap<>();
        if (options.getToolContext() != null) {
            callbackContext.putAll(options.getToolContext());
        }
        callbackContext.remove(TOOL_CALLBACK_REGISTRY_CONTEXT_KEY);

        ToolCallingChatOptions executableOptions = options.mutate()
                .toolCallbacks(new ArrayList<>(executable.values()))
                .toolContext(callbackContext)
                .build();
        return delegate.executeToolCalls(
                new Prompt(prompt.getInstructions(), executableOptions), chatResponse);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolCallback> callbackRegistry(Map<String, Object> toolContext) {
        if (toolContext == null) {
            return Map.of();
        }
        Object registry = toolContext.get(TOOL_CALLBACK_REGISTRY_CONTEXT_KEY);
        if (!(registry instanceof Map<?, ?> callbacks)) {
            return Map.of();
        }
        return (Map<String, ToolCallback>) callbacks;
    }
}
