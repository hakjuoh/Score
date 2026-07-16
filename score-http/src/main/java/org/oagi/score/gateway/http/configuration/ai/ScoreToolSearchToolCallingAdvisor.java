package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchTool;
import org.springframework.ai.tool.toolsearch.eviction.LruEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.ToolIndexEvictionStrategy;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tool-search advisor tailored for requester-scoped connectCenter MCP callbacks.
 *
 * <p>It preserves the context-saving behavior of Spring AI tool search while keeping a
 * private execution registry for exact tool names already known from the conversation.
 * Search responses accumulate, including multiple searches issued in one model turn.</p>
 */
public final class ScoreToolSearchToolCallingAdvisor extends ToolSearchToolCallingAdvisor {

    static final int MAX_RESULTS = 10;

    private static final String CACHED_TOOL_CALLBACKS_KEY =
            ScoreToolSearchToolCallingAdvisor.class.getName() + ".cachedToolCallbacks";
    private static final String SYSTEM_MESSAGE_SUFFIX = """

            You have access to `toolSearchTool` for discovering connectCenter tools on demand.
            Before the first search, identify every capability required by the user's complete
            request, including mutations, relationship operations, and final read-back. Prefer one
            comprehensive search with maxResults 10 that includes all relevant entity and action
            keywords. If one query cannot cover the workflow, issue all necessary searches in
            parallel in the same response. Search results are accumulated and their full tool
            definitions become available on the next step. Do not guess an unknown tool name.
            """;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolIndex toolIndex;
    private final ToolCallback toolSearchToolCallback;
    private final ConcurrentHashMap<String, String> indexedSessionFingerprints =
            new ConcurrentHashMap<>();
    private final ToolIndexEvictionStrategy evictionStrategy = new LruEvictionStrategy(1000);

    public ScoreToolSearchToolCallingAdvisor(ToolIndex toolIndex) {
        this(toolIndex, new RegistryAwareToolCallingManager());
    }

    private ScoreToolSearchToolCallingAdvisor(ToolIndex toolIndex,
                                               RegistryAwareToolCallingManager toolCallingManager) {
        super(toolCallingManager, ToolCallingAdvisor.DEFAULT_ORDER,
                DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, toolIndex,
                SYSTEM_MESSAGE_SUFFIX, true, MAX_RESULTS, true,
                ChatMemory.CONVERSATION_ID, new LruEvictionStrategy(1000));
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.toolSearchToolCallback = MethodToolCallbackProvider.builder()
                .toolObjects(new ToolSearchTool(toolIndex, MAX_RESULTS))
                .build()
                .getToolCallbacks()[0];
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request,
                                                  CallAdvisorChain advisorChain) {
        return initializeSession(request);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request,
                                              CallAdvisorChain advisorChain) {
        return prepareIteration(request);
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest request,
                                                        StreamAdvisorChain advisorChain) {
        return initializeSession(request);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest request,
                                                StreamAdvisorChain advisorChain) {
        return prepareIteration(request);
    }

    @Override
    public void evictSession(String sessionId) {
        toolIndex.clearIndex(sessionId);
        indexedSessionFingerprints.remove(sessionId);
        evictionStrategy.onRemoved(sessionId);
    }

    ChatClientRequest initializeSession(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
            return request;
        }
        String sessionId = sessionId(request.context());
        evictionStrategy.onAccess(sessionId).forEach(this::evictSession);

        List<ToolReference> references = callbacks(options).stream()
                .map(callback -> ToolReference.builder()
                        .toolName(callback.getToolDefinition().name())
                        .summary(callback.getToolDefinition().description())
                        .build())
                .toList();
        String fingerprint = fingerprint(references);
        indexedSessionFingerprints.compute(sessionId, (id, current) -> {
            if (!fingerprint.equals(current)) {
                toolIndex.clearIndex(id);
                toolIndex.indexTools(id, references);
            }
            return fingerprint;
        });

        Map<String, ToolCallback> registry = new LinkedHashMap<>();
        callbacks(options).forEach(callback -> registry.putIfAbsent(
                callback.getToolDefinition().name(), callback));
        request.context().put(CACHED_TOOL_CALLBACKS_KEY,
                Collections.unmodifiableMap(new LinkedHashMap<>(registry)));
        request.context().put(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY, sessionId);

        return request.mutate()
                .prompt(request.prompt().copy().augmentSystemMessage(systemMessage -> systemMessage
                        .copy().mutate()
                        .text(systemMessage.getText() + SYSTEM_MESSAGE_SUFFIX)
                        .build()))
                .build();
    }

    ChatClientRequest prepareIteration(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
            return request;
        }
        Map<String, ToolCallback> registry = callbackRegistry(request.context());
        Map<String, ToolCallback> selected = new LinkedHashMap<>();
        selected.put(toolSearchToolCallback.getToolDefinition().name(), toolSearchToolCallback);

        referencedToolNames(request.prompt().getInstructions()).stream()
                .map(registry::get)
                .filter(Objects::nonNull)
                .forEach(callback -> selected.putIfAbsent(
                        callback.getToolDefinition().name(), callback));
        mentionedToolNames(request.prompt().getInstructions(), registry).stream()
                .map(registry::get)
                .filter(Objects::nonNull)
                .forEach(callback -> selected.putIfAbsent(
                        callback.getToolDefinition().name(), callback));

        ToolCallingChatOptions iterationOptions = options.mutate()
                .toolCallbacks(new ArrayList<>(selected.values()))
                .toolContext(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY,
                        request.context().get(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY))
                .toolContext(RegistryAwareToolCallingManager.TOOL_CALLBACK_REGISTRY_CONTEXT_KEY,
                        registry)
                .build();
        return request.mutate()
                .prompt(request.prompt().mutate().chatOptions(iterationOptions).build())
                .build();
    }

    private List<ToolCallback> callbacks(ToolCallingChatOptions options) {
        return CollectionUtils.isEmpty(options.getToolCallbacks())
                ? List.of() : options.getToolCallbacks();
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolCallback> callbackRegistry(Map<String, Object> context) {
        Object registry = context.get(CACHED_TOOL_CALLBACKS_KEY);
        return registry instanceof Map<?, ?> callbacks
                ? (Map<String, ToolCallback>) callbacks : Map.of();
    }

    private List<String> referencedToolNames(List<Message> messages) {
        List<String> references = new ArrayList<>();
        messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(response -> response.name().equalsIgnoreCase(
                        toolSearchToolCallback.getToolDefinition().name()))
                .forEach(response -> {
                    try {
                        references.addAll(OBJECT_MAPPER.readValue(
                                response.responseData(), new TypeReference<List<String>>() {}));
                    } catch (Exception ignored) {
                        // A malformed search response cannot safely select any callbacks.
                    }
                });
        return references;
    }

    private List<String> mentionedToolNames(List<Message> messages,
                                            Map<String, ToolCallback> registry) {
        List<String> mentioned = new ArrayList<>();
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                continue;
            }
            if (message instanceof AssistantMessage assistantMessage) {
                assistantMessage.getToolCalls().stream()
                        .map(AssistantMessage.ToolCall::name)
                        .filter(registry::containsKey)
                        .forEach(mentioned::add);
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                toolResponseMessage.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::name)
                        .filter(registry::containsKey)
                        .forEach(mentioned::add);
            }
            String text = message.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            registry.keySet().stream()
                    .filter(name -> containsExactToolName(text, name))
                    .forEach(mentioned::add);
        }
        return mentioned.stream().distinct().toList();
    }

    private boolean containsExactToolName(String text, String toolName) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(toolName)
                        + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find();
    }

    private String sessionId(Map<String, Object> context) {
        Object sessionId = context.get(ChatMemory.CONVERSATION_ID);
        if (sessionId == null) {
            throw new IllegalArgumentException("context must contain a conversation ID");
        }
        return sessionId.toString();
    }

    private static String fingerprint(List<ToolReference> references) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            references.stream()
                    .sorted(Comparator.comparing(ToolReference::toolName))
                    .forEachOrdered(reference -> {
                        digest.update(reference.toolName().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(reference.summary().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 1);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
