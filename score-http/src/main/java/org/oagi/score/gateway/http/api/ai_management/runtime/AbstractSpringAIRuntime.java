package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.service.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationToolGuard;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationPermissionMode;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiSystemPrompt;
import org.oagi.score.gateway.http.configuration.ai.TrajectoryRecordingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.util.StringUtils;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared ChatClient orchestration used by the Spring AI provider runtimes. */
abstract class AbstractSpringAIRuntime implements AiRuntime {

    private static final int MAX_READ_BACK_CONTINUATIONS = 2;
    private static final ToolCallingAdvisor DIRECT_TOOL_CALLING_ADVISOR =
            ToolCallingAdvisor.builder().build();
    private static final String READ_BACK_CONTINUATION = """
            INTERNAL_ORCHESTRATION_INSTRUCTION: Continue the signed-in user's original request.
            Do not provide a final answer yet. If any requested mutation remains, call that mutation now.
            If all mutations are complete, call the narrowest read-only get tools needed to read back every
            created or changed record and relationship. Only after successful read-back may you finalize.
            """;

    private final ScoreAiModelRegistry models;
    private final ConnectCenterMcpClientFactory mcpClients;
    private final ToolSearchToolCallingAdvisor toolSearchAdvisor;
    private final ScoreAiSystemPrompt systemPrompt;
    private final AiMutationToolGuard mutationGuard;
    private final AiElicitationService elicitations;

    AbstractSpringAIRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                            ToolSearchToolCallingAdvisor toolSearchAdvisor,
                            ScoreAiSystemPrompt systemPrompt,
                            AiMutationToolGuard mutationGuard) {
        this(models, mcpClients, toolSearchAdvisor, systemPrompt, mutationGuard, null);
    }

    AbstractSpringAIRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                            ToolSearchToolCallingAdvisor toolSearchAdvisor,
                            ScoreAiSystemPrompt systemPrompt,
                            AiMutationToolGuard mutationGuard,
                            AiElicitationService elicitations) {
        this.models = models;
        this.mcpClients = mcpClients;
        this.toolSearchAdvisor = toolSearchAdvisor;
        this.systemPrompt = systemPrompt;
        this.mutationGuard = mutationGuard;
        this.elicitations = elicitations;
    }

    protected final ScoreAiModelRegistry models() {
        return models;
    }

    protected abstract ChatOptions requestOptions(Context context);

    @Override
    public final Result execute(Context context) {
        var request = context.request();
        AiTrajectoryRecorder recorder = context.recorder();
        ChatOptions options = requestOptions(context);
        ScoreAiModelRegistry.RuntimeModel runtimeModel = models.runtimeModel(request.modelName());
        long toolOutputTokenLimit = runtimeModel != null && runtimeModel.contextBudget() != null
                && runtimeModel.contextBudget().toolOutputTokenLimit() != null
                ? runtimeModel.contextBudget().toolOutputTokenLimit() : Long.MAX_VALUE;
        try (ConnectCenterMcpClientFactory.McpSession mcp = elicitations != null
                ? mcpClients.open(context.requester(), elicitation -> handleElicitation(
                        context, request, recorder, elicitation))
                : mcpClients.open(context.requester())) {
            ChatClient.Builder assistantBuilder = models.clientBuilder(request.modelName())
                    .defaultAdvisors(new TrajectoryRecordingAdvisor(recorder));
            AiMutationToolGuard.GuardedToolSession guardedSession = null;
            org.springframework.ai.tool.ToolCallbackProvider executableTools = null;
            if (mcp.client() != null && context.toolsEnabled()) {
                guardedSession = mutationGuard != null
                        ? mutationGuard.session(request, context.requester(),
                                recorder::mutationConfirmationRequired, mcp.tools())
                        : null;
                var guardedTools = guardedSession != null ? guardedSession : mcp.tools();
                executableTools = recorder.recordingTools(guardedTools, toolOutputTokenLimit);
                assistantBuilder.defaultTools(executableTools);
                // A confirmed continuation already has a server-bound target tool.
                // Give the model the guarded callbacks directly so it can resume that
                // invocation and read it back without rediscovering it through
                // toolSearchTool. Other mutations remain protected by the guard.
                if (request.mutationConfirmation() == null) {
                    assistantBuilder.defaultAdvisors(toolSearchAdvisor);
                } else {
                    assistantBuilder.defaultAdvisors(DIRECT_TOOL_CALLING_ADVISOR);
                }
            }
            List<Message> messages = new ArrayList<>(context.history());
            if (request.mutationConfirmation() != null
                    && request.mutationConfirmation().revised()) {
                messages.add(new SystemMessage("""
                        For this turn, the signed-in user has revised and approved exactly one
                        invocation of the %s data-changing tool. Apply the user's current revision
                        with that already-available tool now without searching for it or requesting
                        approval again, then read the changed record back before reporting success.
                        The grant cannot authorize another tool.
                        """.formatted(request.mutationConfirmation().toolName())));
            }
            messages.add(context.userMessage());
            if (guardedSession != null && executableTools != null) {
                guardedSession.executeApproved(executableTools)
                        .ifPresent(execution -> addApprovedExecution(
                                messages, execution, recorder, toolOutputTokenLimit));
            }
            ChatClient assistant = assistantBuilder.build();
            String answer = invoke(assistant, options, request, messages, recorder,
                    context.streamVisibleContent());
            int continuation = 0;
            while (guardedSession != null && guardedSession.mutationCompleted()
                    && !guardedSession.confirmationRequired()
                    && !guardedSession.readAfterLastMutation()
                    && continuation++ < MAX_READ_BACK_CONTINUATIONS) {
                List<Message> continuationMessages = new ArrayList<>(context.history());
                continuationMessages.add(context.userMessage());
                guardedSession.completedMutations()
                        .forEach(execution -> addApprovedExecution(
                                continuationMessages, execution, recorder, toolOutputTokenLimit));
                continuationMessages.add(new AssistantMessage(answer));
                continuationMessages.add(new UserMessage(READ_BACK_CONTINUATION));
                answer = invoke(assistant, options, request, continuationMessages, recorder, false);
            }
            if (guardedSession != null && guardedSession.mutationCompleted()
                    && !guardedSession.confirmationRequired()
                    && !guardedSession.readAfterLastMutation()) {
                throw new IllegalStateException(
                        "The assistant stopped after a mutation without completing read-back.");
            }
            return new Result(answer);
        }
    }

    private McpSchema.ElicitResult handleElicitation(
            Context context, ChatRequest request, AiTrajectoryRecorder recorder,
            McpSchema.ElicitFormRequest elicitation) {
        if (AiMutationPermissionMode.resolve(request.permissionMode())
                == AiMutationPermissionMode.FULL_ACCESS
                && isConfirmationOnly(elicitation.requestedSchema())) {
            return new McpSchema.ElicitResult(
                    McpSchema.ElicitResult.Action.ACCEPT, java.util.Map.of());
        }
        return elicitations.await(context.requester(), request.conversationId(), request.requestId(),
                elicitation, recorder::elicitationRequired);
    }

    private boolean isConfirmationOnly(java.util.Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof java.util.Map<?, ?> properties)
                || !properties.isEmpty()) {
            return false;
        }
        return !(schema.get("required") instanceof java.util.Collection<?> required)
                || required.isEmpty();
    }

    private String invoke(ChatClient assistant, ChatOptions options,
                          ChatRequest request,
                          List<Message> messages, AiTrajectoryRecorder recorder,
                          boolean streamVisibleContent) {
        String answer = assistant.prompt()
                    .options(options.mutate())
                    .system(system -> system.text(systemPrompt.text())
                            .param("pageContext", StringUtils.hasText(request.pageContext())
                                    ? request.pageContext() : "Not provided"))
                    .messages(messages)
                    .advisors(advisor -> advisor
                            .param(ChatMemory.CONVERSATION_ID, request.conversationId())
                            .param(AiTrajectoryRecorder.PHASE_CONTEXT_KEY, "assistant"))
                    .stream()
                    .chatResponse()
                    .map(this::visibleContent)
                    .filter(content -> !content.isEmpty())
                    .doOnNext(content -> {
                        if (streamVisibleContent) {
                            recorder.contentDelta(content);
                        }
                    })
                    .reduce(new StringBuilder(), StringBuilder::append)
                    .map(StringBuilder::toString)
                    .block();
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("The assistant returned an empty response.");
        }
        return answer;
    }

    private void addApprovedExecution(List<Message> messages,
                                      AiMutationToolGuard.ApprovedExecution execution,
                                      AiTrajectoryRecorder recorder, long toolOutputTokenLimit) {
        String callId = "approved-" + UUID.randomUUID();
        messages.add(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(callId, "function", execution.toolName(),
                        execution.arguments()))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, execution.toolName(),
                        recorder.limitToolOutput(execution.result(), toolOutputTokenLimit,
                                execution.toolName())))).build());
    }

    private String visibleContent(ChatResponse response) {
        if (response == null) {
            return "";
        }
        return response.getResults().stream()
                .map(generation -> generation.getOutput())
                .filter(output -> !isReasoning(output))
                .map(AssistantMessage::getText)
                .filter(text -> text != null && !text.isEmpty())
                .reduce("", String::concat);
    }

    private boolean isReasoning(AssistantMessage output) {
        return output.getMetadata().containsKey("signature")
                || output.getMetadata().containsKey("data")
                || Boolean.TRUE.equals(output.getMetadata().get("thinking"));
    }
}
