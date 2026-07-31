package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;
import org.oagi.score.gateway.http.api.ai_management.provider.AiProviderRetryExecutor;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Owns provider retry, guarded prompt assembly, and streamed answer segmentation. */
final class AiChatModelInvoker {

    private final AiProviderRetryExecutor providerRetry;
    private final AiRequestRegistry requests;
    private final AiExecutionInstructions instructions;
    private final AiModelInputGuard modelInputGuard;

    AiChatModelInvoker(AiProviderRetryExecutor providerRetry, AiRequestRegistry requests,
                       AiExecutionInstructions instructions, AiModelInputGuard modelInputGuard) {
        this.providerRetry = providerRetry;
        this.requests = requests;
        this.instructions = instructions;
        this.modelInputGuard = modelInputGuard;
    }

    String invoke(ChatClient assistant, ChatOptions options, ChatRequest request,
                  List<Message> messages, AiTrajectoryRecorder recorder,
                  boolean internalPersona, ExecutionScope scope, ExecutionState executionState,
                  Agent.Instruction instruction, Runnable progress) {
        if (providerRetry == null) {
            return attemptInvoke(assistant, options, request, messages, recorder,
                    internalPersona, scope, instruction, progress);
        }
        // A provider attempt that executed a data-changing tool is terminal.
        return providerRetry.execute(request, recorder,
                () -> Math.max(executionState.completedChanges(),
                        recorder.executedChangeToolCallCount()), executionState,
                () -> attemptInvoke(assistant, options, request, messages, recorder,
                        internalPersona, scope, instruction, progress));
    }

    private String attemptInvoke(ChatClient assistant, ChatOptions options,
                                 ChatRequest request, List<Message> messages,
                                 AiTrajectoryRecorder recorder, boolean internalPersona,
                                 ExecutionScope scope, Agent.Instruction instruction,
                                 Runnable progress) {
        recorder.verifyActive();
        if (Thread.currentThread().isInterrupted()
                || requests != null && requests.shouldDiscardResult(scope.requestId())) {
            throw new CancellationException(
                    "The assistant request stopped before model execution.");
        }
        progress.run();
        StringBuilder answer = new StringBuilder();
        long[] toolBoundary = {recorder.completedToolCallCount()};
        List<Message> requestMessages = new ArrayList<>(messages.size() + 1);
        requestMessages.addAll(messages);
        int guardedUserIndex = modelInputGuard.lastUserMessageIndex(requestMessages);
        // Internal agents own a stable system prompt. Volatile page data stays in an
        // untrusted user-context block so provider prefix caching remains effective.
        if (!internalPersona) {
            requestMessages.add(new UserMessage(AiSensitiveDataRedactor.redactText(
                    requestScopedInput(request, instructions))));
        }
        requestMessages = modelInputGuard.apply(request, requestMessages, guardedUserIndex, scope);
        assistant.prompt().options(options.mutate())
                .system(system -> system.text(instruction.value()))
                .messages(requestMessages)
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, request.conversationId())
                        .param(AiTrajectoryRecorder.PHASE_CONTEXT_KEY, "assistant"))
                .stream().chatResponse()
                .doOnNext(ignored -> progress.run())
                .map(SpringAiResponseContent::visibleStreaming)
                .filter(content -> !content.isEmpty())
                .doOnNext(content -> {
                    long boundary = recorder.completedToolCallCount();
                    // Text before a Tool call is interim narration. The first substantive
                    // post-Tool chunk starts the answer segment that may reach the user.
                    if (boundary != toolBoundary[0] && StringUtils.hasText(content)) {
                        toolBoundary[0] = boundary;
                        answer.setLength(0);
                    }
                    answer.append(content);
                    // Candidate text remains private until the application output guardrail
                    // accepts or rewrites the complete response.
                })
                .then().block();
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("The assistant returned an empty response.");
        }
        return answer.toString();
    }

    static String requestScopedInput(ChatRequest request) {
        return requestScopedInput(request, AiExecutionInstructions.bundled());
    }

    private static String requestScopedInput(ChatRequest request,
                                             AiExecutionInstructions instructions) {
        String pageContext = request != null && StringUtils.hasText(request.pageContext())
                ? request.pageContext() : "Not provided";
        return instructions.render(AiExecutionInstructions.Template.REQUEST_SCOPED_INPUT,
                Map.of("pageContext", pageContext)).value();
    }
}
