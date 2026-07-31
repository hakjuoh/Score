package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedChange;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.UUID;

/** Reconstructs already-approved changes as canonical model tool-call history. */
final class AiApprovedChangeMessages {

    private AiApprovedChangeMessages() {
    }

    static void append(List<Message> messages, AiApprovedExecution execution,
                       AiTrajectoryRecorder recorder, long toolOutputTokenLimit) {
        append(messages, execution.toolName(), execution.arguments(), execution.result(),
                recorder, toolOutputTokenLimit);
    }

    static void append(List<Message> messages, AiResolvedChange resolution,
                       AiTrajectoryRecorder recorder, long toolOutputTokenLimit) {
        append(messages, resolution.toolName(), resolution.arguments(), resolution.result(),
                recorder, toolOutputTokenLimit);
    }

    private static void append(List<Message> messages, String toolName, String arguments,
                               String result, AiTrajectoryRecorder recorder,
                               long toolOutputTokenLimit) {
        String callId = "approved-" + UUID.randomUUID();
        messages.add(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(callId, "function", toolName, arguments))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, toolName,
                        recorder.limitToolOutput(result, toolOutputTokenLimit, toolName)))).build());
    }
}
