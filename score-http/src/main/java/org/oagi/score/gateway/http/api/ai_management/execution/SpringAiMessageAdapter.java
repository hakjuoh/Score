package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Objects;

/** Canonical-message mapping owned solely by the Spring AI adapter. */
final class SpringAiMessageAdapter {

    private SpringAiMessageAdapter() {
    }

    static AiMessage toCore(Message message) {
        if (message instanceof SystemMessage system) {
            return new AiMessage.System(system.getText());
        }
        if (message instanceof UserMessage user) {
            return SpringAiUserMessageAdapter.toCore(user);
        }
        if (message instanceof AssistantMessage assistant) {
            return new AiMessage.Assistant(assistant.getText());
        }
        return new AiMessage.ToolResult("tool-result", "tool",
                Objects.requireNonNullElse(message.getText(), ""));
    }

    static Message toProvider(AiMessage message) {
        if (message instanceof AiMessage.System system) {
            return new SystemMessage(system.content());
        }
        if (message instanceof AiMessage.User user) {
            return SpringAiUserMessageAdapter.toSpring(user);
        }
        if (message instanceof AiMessage.Assistant assistant) {
            return new AssistantMessage(assistant.content());
        }
        if (message instanceof AiMessage.ToolCall call) {
            return AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(call.callId(), "function", call.toolName(),
                            call.arguments()))).build();
        }
        AiMessage.ToolResult result = (AiMessage.ToolResult) message;
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(result.callId(), result.toolName(),
                        result.result()))).build();
    }
}
