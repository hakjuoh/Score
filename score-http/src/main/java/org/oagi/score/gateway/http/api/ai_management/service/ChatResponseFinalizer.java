package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentIdentityProvider;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileDescriptor;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies the shared disclosure policy and builds the final public chat response. */
final class ChatResponseFinalizer {

    private final AgentIdentityProvider rootAgentIdentity;
    private final ChatOutputDiscloser outputDiscloser;
    private final ChatResultCommitter results;
    private final AiFileService files;

    ChatResponseFinalizer(AgentIdentityProvider rootAgentIdentity,
                          ChatOutputDiscloser outputDiscloser,
                          ChatResultCommitter results, AiFileService files) {
        this.rootAgentIdentity = rootAgentIdentity;
        this.outputDiscloser = outputDiscloser;
        this.results = results;
        this.files = files;
    }

    FinalizedOutput finalizeOutput(ChatTurnOutput output, ChatRequest request,
                                   List<Message> history, UserMessage userMessage,
                                   ScoreUser requester, AiTrajectoryRecorder recorder,
                                   ExecutionScope scope) {
        results.rejectDiscarded(request.requestId());
        if (!StringUtils.hasText(output.answer())) {
            throw new IllegalStateException("The assistant returned an empty response.");
        }
        ChatDisclosure disclosure = outputDiscloser.disclose(
                output, request, history, userMessage, requester, recorder, scope);
        String answer = disclosure.answer();
        String tracedAgentId = Objects.toString(
                disclosure.metadata().get("agentId"), null);
        String agentId = StringUtils.hasText(tracedAgentId)
                ? tracedAgentId : rootAgentIdentity.rootAgentId();
        recorder.contentDelta(answer);
        return new FinalizedOutput(agentId, answer,
                outputDiscloser.committedTrace(disclosure, agentId));
    }

    ChatResponse response(FinalizedOutput output, ChatRequest request,
                          ScoreUser requester, ExecutionScope scope,
                          List<String> progressMessages) {
        List<AiFileDescriptor> createdFiles = files != null
                ? files.ensureRequestedFiles(
                requester, scope, request.prompt(), output.answer()) : List.of();
        return new ChatResponse(output.agentId(), output.answer(),
                request.conversationId(), false, List.copyOf(progressMessages),
                createdFiles, List.of());
    }

    record FinalizedOutput(String agentId, String answer,
                           Map<String, Object> committedTrace) {
    }
}
