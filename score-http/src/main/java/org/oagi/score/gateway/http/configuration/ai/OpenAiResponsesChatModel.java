package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.BadRequestException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCompactionItem;
import com.openai.models.responses.ResponseCompactionItemParam;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring AI {@link ChatModel} adapter for OpenAI's Responses API.
 *
 * <p>Spring AI 2.0's OpenAI chat model only uses Chat Completions. Newer
 * reasoning models require tool calls with reasoning effort to use Responses,
 * so this adapter translates Spring AI messages and tools into Responses items
 * while leaving Spring AI's tool execution advisor in charge of the tool loop.</p>
 */
public final class OpenAiResponsesChatModel implements ChatModel {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenAIClient client;
    private final OpenAiChatOptions options;
    private final Long compactThreshold;
    private final AtomicBoolean nativeCompactionSupported = new AtomicBoolean(true);
    private final Map<String, CachedToolResponse> toolContinuations = new ConcurrentHashMap<>();

    static OpenAiResponsesChatModel create(OpenAiChatOptions options, Duration requestTimeout) {
        return create(options, requestTimeout, null);
    }

    static OpenAiResponsesChatModel create(OpenAiChatOptions options, Duration requestTimeout,
                                           Long compactThreshold) {
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                responsesBaseUrl(options), options.getApiKey(), options.getCredential(),
                options.getMicrosoftDeploymentName(),
                // Azure's unified /openai/v1 endpoint does not need the legacy
                // api-version query parameter.
                options.isMicrosoftFoundry() ? null : options.getMicrosoftFoundryServiceVersion(),
                options.getOrganizationId(), options.isMicrosoftFoundry(), options.isGitHubModels(),
                options.getModel(), requestTimeout, options.getMaxRetries(), options.getProxy(),
                options.getCustomHeaders(), ObservationRegistry.NOOP, null, List.of());
        return new OpenAiResponsesChatModel(client, options, compactThreshold);
    }

    static String responsesBaseUrl(OpenAiChatOptions options) {
        String baseUrl = options.getBaseUrl();
        if (!options.isMicrosoftFoundry() || !StringUtils.hasText(baseUrl)) return baseUrl;
        String normalized = baseUrl.replaceAll("/+$", "");
        return normalized.endsWith("/openai/v1") ? normalized : normalized + "/openai/v1";
    }

    OpenAiResponsesChatModel(OpenAIClient client, OpenAiChatOptions options) {
        this(client, options, null);
    }

    OpenAiResponsesChatModel(OpenAIClient client, OpenAiChatOptions options, Long compactThreshold) {
        this.client = client;
        this.options = options;
        this.compactThreshold = compactThreshold != null && compactThreshold > 0 ? compactThreshold : null;
    }

    @Override
    public OpenAiChatOptions getOptions() {
        return options;
    }

    @Override
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    public ChatOptions getDefaultOptions() {
        return options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        OpenAiChatOptions requestOptions = prompt.getOptions() instanceof OpenAiChatOptions openAiOptions
                ? openAiOptions : options;
        ConvertedInput converted = convertInput(prompt.getInstructions());
        boolean nativeCompaction = compactThreshold != null && nativeCompactionSupported.get();
        Response response;
        try {
            response = client.responses().create(createRequest(requestOptions, converted, nativeCompaction));
        } catch (BadRequestException exception) {
            if (!nativeCompaction || !unsupportedContextManagement(exception)) throw exception;
            nativeCompactionSupported.set(false);
            response = client.responses().create(createRequest(requestOptions, converted, false));
        }
        if (ResponseStatus.FAILED.equals(response.status().orElse(null))) {
            // A FAILED response arrives over HTTP 200, so no SDK exception carries it.
            // The retry markers make server-side generation failures recoverable by the
            // application-level provider retry loop, matching the SDK-thrown statuses.
            String detail = "OpenAI Responses API failed: "
                    + response.error().map(error -> error.message()).orElse("unknown error");
            boolean transientFailure = response.error()
                    .map(error -> com.openai.models.responses.ResponseError.Code.SERVER_ERROR
                            .equals(error.code())
                            || com.openai.models.responses.ResponseError.Code.RATE_LIMIT_EXCEEDED
                            .equals(error.code()))
                    .orElse(false);
            if (transientFailure) {
                throw new org.springframework.ai.retry.TransientAiException(detail);
            }
            throw new org.springframework.ai.retry.NonTransientAiException(detail);
        }
        converted.consumedContinuationIds().forEach(toolContinuations::remove);
        return toChatResponse(response);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // The surrounding Spring AI advisor performs and streams the recursive tool
        // loop. A single complete Response keeps that loop compatible while avoiding
        // Chat Completions entirely.
        return Flux.defer(() -> Flux.just(call(prompt)));
    }

    private ResponseCreateParams createRequest(OpenAiChatOptions requestOptions, ConvertedInput converted,
                                               boolean nativeCompaction) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(requestOptions.getModel())
                .inputOfResponse(converted.items());
        if (nativeCompaction) {
            builder.addContextManagement(ResponseCreateParams.ContextManagement.builder()
                    .type("compaction")
                    .compactThreshold(compactThreshold)
                    .build());
        }
        if (StringUtils.hasText(converted.instructions())) {
            builder.instructions(converted.instructions());
        }
        Integer maxOutputTokens = requestOptions.getMaxCompletionTokens() != null
                ? requestOptions.getMaxCompletionTokens() : requestOptions.getMaxTokens();
        if (maxOutputTokens != null) builder.maxOutputTokens(maxOutputTokens.longValue());
        if (requestOptions.getParallelToolCalls() != null) {
            builder.parallelToolCalls(requestOptions.getParallelToolCalls());
        }
        if (requestOptions.getStore() != null) builder.store(requestOptions.getStore());
        if (requestOptions.getMetadata() != null && !requestOptions.getMetadata().isEmpty()) {
            Map<String, JsonValue> metadata = new LinkedHashMap<>();
            requestOptions.getMetadata().forEach((name, value) -> metadata.put(name, JsonValue.from(value)));
            builder.metadata(ResponseCreateParams.Metadata.builder()
                    .additionalProperties(metadata).build());
        }
        if (requestOptions.getTemperature() != null) builder.temperature(requestOptions.getTemperature());
        if (requestOptions.getTopP() != null) builder.topP(requestOptions.getTopP());
        if (requestOptions.getTopLogprobs() != null) {
            builder.topLogprobs(requestOptions.getTopLogprobs().longValue());
        }
        if (StringUtils.hasText(requestOptions.getReasoningEffort())) {
            builder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(requestOptions.getReasoningEffort().strip().toLowerCase()))
                    .build());
        }
        if (StringUtils.hasText(requestOptions.getVerbosity())) {
            builder.text(ResponseTextConfig.builder()
                    .verbosity(ResponseTextConfig.Verbosity.of(
                            requestOptions.getVerbosity().strip().toLowerCase()))
                    .build());
        }
        if (StringUtils.hasText(requestOptions.getServiceTier())) {
            builder.serviceTier(ResponseCreateParams.ServiceTier.of(
                    requestOptions.getServiceTier().strip().toLowerCase()));
        }
        if (StringUtils.hasText(requestOptions.getPromptCacheKey())) {
            builder.promptCacheKey(requestOptions.getPromptCacheKey().strip());
        }
        if (StringUtils.hasText(requestOptions.getUser())) builder.user(requestOptions.getUser().strip());
        addToolChoice(builder, requestOptions.getToolChoice());
        addTools(builder, requestOptions);
        if (requestOptions.getExtraBody() != null) {
            requestOptions.getExtraBody().forEach((name, value) ->
                    builder.putAdditionalBodyProperty(name, JsonValue.from(value)));
        }
        return builder.build();
    }

    private boolean unsupportedContextManagement(BadRequestException exception) {
        String message = String.valueOf(exception.getMessage()).toLowerCase();
        return message.contains("context_management") || message.contains("compact_threshold")
                || message.contains("compaction");
    }

    private void addToolChoice(ResponseCreateParams.Builder builder, Object toolChoice) {
        if (!(toolChoice instanceof String choice) || !StringUtils.hasText(choice)) return;
        String normalized = choice.strip().toLowerCase();
        if (Set.of("auto", "none", "required").contains(normalized)) {
            builder.toolChoice(ToolChoiceOptions.of(normalized));
        }
    }

    private void addTools(ResponseCreateParams.Builder builder, OpenAiChatOptions requestOptions) {
        if (!(requestOptions instanceof ToolCallingChatOptions toolOptions)) return;
        for (ToolCallback callback : toolOptions.getToolCallbacks() != null
                ? toolOptions.getToolCallbacks() : List.<ToolCallback>of()) {
            ToolDefinition definition = callback.getToolDefinition();
            FunctionTool.Builder tool = FunctionTool.builder()
                    .name(definition.name())
                    // MCP schemas are not guaranteed to satisfy OpenAI strict-mode
                    // constraints. Preserve the old best-effort behavior explicitly.
                    .strict(false)
                    .parameters(parameters(definition.inputSchema()));
            if (StringUtils.hasText(definition.description())) {
                tool.description(definition.description());
            }
            builder.addTool(tool.build());
        }
    }

    private FunctionTool.Parameters parameters(String schema) {
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(schema, new TypeReference<>() {});
            Map<String, JsonValue> json = new LinkedHashMap<>();
            values.forEach((name, value) -> json.put(name, JsonValue.from(value)));
            return FunctionTool.Parameters.builder().additionalProperties(json).build();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON schema for OpenAI function tool", exception);
        }
    }

    private ConvertedInput convertInput(List<Message> messages) {
        String instructions = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        Continuation continuation = continuation(messages);
        List<ResponseInputItem> input = new ArrayList<>();
        int regularEnd = continuation != null ? continuation.assistantIndex() : messages.size();
        for (int index = 0; index < regularEnd; index++) {
            addMessage(input, messages.get(index));
        }
        Set<String> consumedIds = new LinkedHashSet<>();
        if (continuation != null) {
            List<ResponseOutputItem> output = continuation.cached().output();
            int first = 0;
            for (int index = output.size() - 1; index >= 0; index--) {
                if (output.get(index).isCompaction()) {
                    first = index;
                    break;
                }
            }
            output.subList(first, output.size()).forEach(item -> addOutputItem(input, item));
            addToolResponses(input, continuation.toolResponse());
            consumedIds.addAll(continuation.cached().callIds());
        } else {
            for (int index = regularEnd; index < messages.size(); index++) {
                addMessage(input, messages.get(index));
            }
        }
        if (input.isEmpty()) {
            input.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER).content("").build()));
        }
        return new ConvertedInput(instructions, List.copyOf(input), Set.copyOf(consumedIds));
    }

    private Continuation continuation(List<Message> messages) {
        if (messages.size() < 2) return null;
        int assistantIndex = messages.size() - 2;
        if (!(messages.get(assistantIndex) instanceof AssistantMessage assistant)
                || !assistant.hasToolCalls()
                || !(messages.getLast() instanceof ToolResponseMessage toolResponse)) {
            return null;
        }
        CachedToolResponse cached = null;
        for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
            CachedToolResponse candidate = toolContinuations.get(toolCall.id());
            if (candidate == null || cached != null && candidate != cached) return null;
            cached = candidate;
        }
        return cached != null ? new Continuation(assistantIndex, cached, toolResponse) : null;
    }

    private void addMessage(List<ResponseInputItem> input, Message message) {
        if (message.getMessageType() == MessageType.SYSTEM) return;
        if (message instanceof ToolResponseMessage toolResponse) {
            addToolResponses(input, toolResponse);
            return;
        }
        if (message instanceof AssistantMessage assistant) {
            if (StringUtils.hasText(assistant.getText())) {
                input.add(easyMessage(EasyInputMessage.Role.ASSISTANT, assistant));
            }
            assistant.getToolCalls().forEach(toolCall -> input.add(ResponseInputItem.ofFunctionCall(
                    ResponseFunctionToolCall.builder()
                            .callId(toolCall.id())
                            .name(toolCall.name())
                            .arguments(StringUtils.hasText(toolCall.arguments()) ? toolCall.arguments() : "{}")
                            .status(ResponseFunctionToolCall.Status.COMPLETED)
                            .build())));
            return;
        }
        input.add(easyMessage(EasyInputMessage.Role.USER, message));
    }

    private ResponseInputItem easyMessage(EasyInputMessage.Role role, Message message) {
        EasyInputMessage.Builder builder = EasyInputMessage.builder().role(role);
        if (!(message instanceof MediaContent mediaContent) || mediaContent.getMedia().isEmpty()) {
            return ResponseInputItem.ofEasyInputMessage(builder.content(message.getText()).build());
        }
        List<ResponseInputContent> content = new ArrayList<>();
        if (StringUtils.hasText(message.getText())) {
            content.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text(message.getText()).build()));
        }
        for (Media media : mediaContent.getMedia()) {
            String mimeType = media.getMimeType().toString();
            String data = Base64.getEncoder().encodeToString(media.getDataAsByteArray());
            if (mimeType.startsWith("image/")) {
                content.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                        .detail(ResponseInputImage.Detail.AUTO)
                        .imageUrl("data:" + mimeType + ";base64," + data)
                        .build()));
            } else if ("application/pdf".equals(mimeType)) {
                String filename = StringUtils.hasText(media.getName()) ? media.getName() : "attachment.pdf";
                if (!filename.toLowerCase().endsWith(".pdf")) filename += ".pdf";
                content.add(ResponseInputContent.ofInputFile(ResponseInputFile.builder()
                        .fileData("data:" + mimeType + ";base64," + data)
                        .filename(filename)
                        .build()));
            } else {
                content.add(ResponseInputContent.ofInputText(ResponseInputText.builder()
                        .text(new String(media.getDataAsByteArray(), StandardCharsets.UTF_8)).build()));
            }
        }
        return ResponseInputItem.ofEasyInputMessage(
                builder.contentOfResponseInputMessageContentList(content).build());
    }

    private void addToolResponses(List<ResponseInputItem> input, ToolResponseMessage toolResponse) {
        toolResponse.getResponses().forEach(response -> input.add(ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                        .callId(response.id())
                        .output(response.responseData() != null ? response.responseData() : "")
                        .build())));
    }

    private void addOutputItem(List<ResponseInputItem> input, ResponseOutputItem item) {
        if (item.isMessage()) {
            input.add(ResponseInputItem.ofResponseOutputMessage(item.asMessage()));
        } else if (item.isFunctionCall()) {
            input.add(ResponseInputItem.ofFunctionCall(item.asFunctionCall()));
        } else if (item.isReasoning()) {
            input.add(ResponseInputItem.ofReasoning(item.asReasoning()));
        } else if (item.isCompaction()) {
            ResponseCompactionItem compaction = item.asCompaction();
            input.add(ResponseInputItem.ofCompaction(ResponseCompactionItemParam.builder()
                    .id(compaction.id())
                    .encryptedContent(compaction.encryptedContent())
                    .build()));
        }
    }

    private ChatResponse toChatResponse(Response response) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            if (item.isMessage()) {
                item.asMessage().content().forEach(content -> {
                    if (content.isOutputText()) text.append(content.asOutputText().text());
                    else if (content.isRefusal()) text.append(content.asRefusal().refusal());
                });
            } else if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                toolCalls.add(new AssistantMessage.ToolCall(
                        call.callId(), "function", call.name(), call.arguments()));
            }
        }
        if (!toolCalls.isEmpty()) {
            if (toolContinuations.size() > 1024) toolContinuations.clear();
            CachedToolResponse cached = new CachedToolResponse(
                    List.copyOf(response.output()), toolCalls.stream()
                    .map(AssistantMessage.ToolCall::id).collect(java.util.stream.Collectors.toUnmodifiableSet()));
            cached.callIds().forEach(callId -> toolContinuations.put(callId, cached));
        }
        AssistantMessage assistant = AssistantMessage.builder()
                .content(text.toString())
                .properties(Map.of("response_id", response.id()))
                .toolCalls(toolCalls)
                .build();
        String finishReason = !toolCalls.isEmpty() ? "TOOL_CALLS"
                : response.status().map(ResponseStatus::asString).orElse("completed");
        Generation generation = new Generation(assistant, ChatGenerationMetadata.builder()
                .finishReason(finishReason).build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(response.id())
                .model(response.model().asString())
                .keyValue("created", response.createdAt());
        response.usage().ifPresent(usage -> metadata.usage(new DefaultUsage(
                safeInt(usage.inputTokens()), safeInt(usage.outputTokens()), safeInt(usage.totalTokens()), usage)));
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private int safeInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private record ConvertedInput(String instructions, List<ResponseInputItem> items,
                                  Set<String> consumedContinuationIds) {}

    private record CachedToolResponse(List<ResponseOutputItem> output, Set<String> callIds) {}

    private record Continuation(int assistantIndex, CachedToolResponse cached,
                                ToolResponseMessage toolResponse) {}
}
