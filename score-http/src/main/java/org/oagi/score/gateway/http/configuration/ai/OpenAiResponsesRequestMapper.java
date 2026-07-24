package org.oagi.score.gateway.http.configuration.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps Spring AI prompts to the OpenAI Responses API without executing tools. */
final class OpenAiResponsesRequestMapper {

    static final String REASONING_ITEMS_METADATA_KEY = "openai.responses.reasoning_items";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolCallingManager toolCallingManager;
    private final ObjectMapper objectMapper;

    OpenAiResponsesRequestMapper() {
        this(ToolCallingManager.builder().build(), new ObjectMapper());
    }

    OpenAiResponsesRequestMapper(ToolCallingManager toolCallingManager, ObjectMapper objectMapper) {
        this.toolCallingManager = toolCallingManager;
        this.objectMapper = objectMapper;
    }

    ResponseCreateParams create(List<Message> messages, OpenAiChatOptions options) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(StringUtils.hasText(options.getDeploymentName())
                        ? options.getDeploymentName() : options.getModel())
                .inputOfResponse(toInput(messages));

        Integer outputLimit = options.getMaxCompletionTokens() != null
                ? options.getMaxCompletionTokens() : options.getMaxTokens();
        if (outputLimit != null) builder.maxOutputTokens(outputLimit.longValue());
        if (options.getTemperature() != null) builder.temperature(options.getTemperature());
        if (options.getTopP() != null) builder.topP(options.getTopP());
        if (options.getTopLogprobs() != null) builder.topLogprobs(options.getTopLogprobs().longValue());
        if (options.getParallelToolCalls() != null) {
            builder.parallelToolCalls(options.getParallelToolCalls());
        }
        if (options.getStore() != null) builder.store(options.getStore());
        if (StringUtils.hasText(options.getUser())) builder.user(options.getUser());
        if (StringUtils.hasText(options.getPromptCacheKey())) {
            builder.promptCacheKey(options.getPromptCacheKey());
        }
        if (StringUtils.hasText(options.getServiceTier())) {
            builder.serviceTier(ResponseCreateParams.ServiceTier.of(options.getServiceTier()));
        }
        if (!CollectionUtils.isEmpty(options.getMetadata())) {
            ResponseCreateParams.Metadata.Builder metadata = ResponseCreateParams.Metadata.builder();
            options.getMetadata().forEach((key, value) ->
                    metadata.putAdditionalProperty(key, JsonValue.from(value)));
            builder.metadata(metadata.build());
        }
        if (StringUtils.hasText(options.getReasoningEffort())) {
            builder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(options.getReasoningEffort().strip().toLowerCase()))
                    .build());
            builder.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        }
        if (StringUtils.hasText(options.getVerbosity())) {
            builder.text(ResponseTextConfig.builder()
                    .verbosity(ResponseTextConfig.Verbosity.of(
                            options.getVerbosity().strip().toLowerCase()))
                    .build());
        }
        addTools(builder, options);
        addToolChoice(builder, options.getToolChoice());
        if (!CollectionUtils.isEmpty(options.getExtraBody())) {
            options.getExtraBody().forEach((key, value) ->
                    builder.putAdditionalBodyProperty(key, JsonValue.from(value)));
        }
        if (!CollectionUtils.isEmpty(options.getCustomHeaders())) {
            options.getCustomHeaders().forEach(builder::putAdditionalHeader);
        }
        return builder.build();
    }

    private List<ResponseInputItem> toInput(List<Message> messages) {
        List<ResponseInputItem> input = new ArrayList<>();
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.USER) {
                input.add(ResponseInputItem.ofEasyInputMessage(userMessage(message)));
            } else if (message.getMessageType() == MessageType.SYSTEM) {
                input.add(easyMessage(EasyInputMessage.Role.SYSTEM, message.getText()));
            } else if (message.getMessageType() == MessageType.ASSISTANT) {
                addAssistantMessage(input, (AssistantMessage) message);
            } else if (message.getMessageType() == MessageType.TOOL) {
                addToolResponses(input, (ToolResponseMessage) message);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported Responses API message type: " + message.getMessageType());
            }
        }
        return input;
    }

    private EasyInputMessage userMessage(Message message) {
        if (!(message instanceof UserMessage userMessage)
                || CollectionUtils.isEmpty(userMessage.getMedia())) {
            return EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content(java.util.Objects.requireNonNullElse(message.getText(), ""))
                    .build();
        }
        List<ResponseInputContent> content = new ArrayList<>();
        if (StringUtils.hasText(message.getText())) {
            content.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text(message.getText()).build()));
        }
        userMessage.getMedia().stream().map(this::mediaContent).forEach(content::add);
        return EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .contentOfResponseInputMessageContentList(content)
                .build();
    }

    private ResponseInputContent mediaContent(Media media) {
        String mediaType = media.getMimeType().toString();
        if (mediaType.startsWith("image/")) {
            return ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl(mediaUrl(mediaType, media))
                    .build());
        }
        if ("application/pdf".equals(mediaType)) {
            return ResponseInputContent.ofInputFile(ResponseInputFile.builder()
                    .filename(StringUtils.hasText(media.getName()) ? media.getName() : "attachment.pdf")
                    .fileData(mediaUrl(mediaType, media))
                    .build());
        }
        throw new IllegalArgumentException(
                "Unsupported Responses API media type: " + mediaType);
    }

    private String mediaUrl(String mediaType, Media media) {
        Object data = media.getData();
        if (data instanceof URI uri) return uri.toString();
        if (data instanceof String value) return value;
        return "data:" + mediaType + ";base64,"
                + Base64.getEncoder().encodeToString(media.getDataAsByteArray());
    }

    private void addAssistantMessage(List<ResponseInputItem> input, AssistantMessage message) {
        replayReasoningItems(message).forEach(item ->
                input.add(ResponseInputItem.ofReasoning(item)));
        if (StringUtils.hasText(message.getText())) {
            input.add(easyMessage(EasyInputMessage.Role.ASSISTANT, message.getText()));
        }
        message.getToolCalls().forEach(call ->
                input.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                        .callId(call.id())
                        .name(call.name())
                        .arguments(call.arguments())
                        .build())));
    }

    private List<ResponseReasoningItem> replayReasoningItems(AssistantMessage message) {
        Object raw = message.getMetadata().get(REASONING_ITEMS_METADATA_KEY);
        if (!(raw instanceof List<?> values)) return List.of();
        List<ResponseReasoningItem> items = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            String id = java.util.Objects.toString(map.get("id"), "");
            String encryptedContent = java.util.Objects.toString(
                    map.get("encryptedContent"), "");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(encryptedContent)) continue;
            ResponseReasoningItem.Builder item = ResponseReasoningItem.builder()
                    .id(id)
                    .summary(reasoningSummaries(map.get("summary")))
                    .encryptedContent(encryptedContent);
            String status = java.util.Objects.toString(map.get("status"), "");
            if (StringUtils.hasText(status)) {
                item.status(ResponseReasoningItem.Status.of(status));
            }
            items.add(item.build());
        }
        return items;
    }

    private List<ResponseReasoningItem.Summary> reasoningSummaries(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        return values.stream()
                .map(value -> java.util.Objects.toString(value, ""))
                .filter(StringUtils::hasText)
                .map(text -> ResponseReasoningItem.Summary.builder().text(text).build())
                .toList();
    }

    private void addToolResponses(List<ResponseInputItem> input, ToolResponseMessage message) {
        if (message.getResponses().isEmpty()) {
            throw new IllegalArgumentException(
                    "Responses API tool messages must contain a call response.");
        }
        message.getResponses().forEach(response ->
                input.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                .callId(response.id())
                                .output(response.responseData())
                                .build())));
    }

    private ResponseInputItem easyMessage(EasyInputMessage.Role role, String content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(role)
                .content(java.util.Objects.requireNonNullElse(content, ""))
                .build());
    }

    private void addTools(ResponseCreateParams.Builder builder, OpenAiChatOptions options) {
        List<ToolDefinition> definitions = toolCallingManager.resolveToolDefinitions(options);
        for (ToolDefinition definition : definitions) {
            FunctionTool.Builder tool = FunctionTool.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .strict(false);
            if (StringUtils.hasText(definition.inputSchema())) {
                try {
                    Map<String, Object> schema = objectMapper.readValue(
                            definition.inputSchema(), MAP_TYPE);
                    Map<String, JsonValue> parameters = new LinkedHashMap<>();
                    schema.forEach((key, value) ->
                            parameters.put(key, JsonValue.from(value)));
                    tool.parameters(FunctionTool.Parameters.builder()
                            .putAllAdditionalProperties(parameters)
                            .build());
                } catch (JsonProcessingException failure) {
                    throw new IllegalArgumentException(
                            "Invalid input schema for AI tool '" + definition.name() + "'.",
                            failure);
                }
            }
            builder.addTool(tool.build());
        }
    }

    private void addToolChoice(ResponseCreateParams.Builder builder, Object toolChoice) {
        if (toolChoice == null) return;
        if (toolChoice instanceof String value
                && List.of("auto", "none", "required").contains(value.toLowerCase())) {
            builder.toolChoice(ToolChoiceOptions.of(value.toLowerCase()));
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported Responses API tool choice: " + toolChoice);
    }
}
