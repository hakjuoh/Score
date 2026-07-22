package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral canonical message used at the Agent execution boundary. */
public sealed interface AiMessage permits AiMessage.System, AiMessage.User, AiMessage.Assistant,
        AiMessage.ToolCall, AiMessage.ToolResult {

    String content();

    record System(String content) implements AiMessage {
        public System { content = text(content); }
    }

    record User(String content, List<Attachment> attachments) implements AiMessage {
        public User {
            content = text(content);
            attachments = attachments != null ? List.copyOf(attachments) : List.of();
        }
        public User(String content) { this(content, List.of()); }
    }

    record Assistant(String content) implements AiMessage {
        public Assistant { content = Objects.requireNonNullElse(content, ""); }
    }

    record ToolCall(String callId, String toolName, String arguments) implements AiMessage {
        public ToolCall {
            callId = text(callId); toolName = text(toolName);
            arguments = Objects.requireNonNullElse(arguments, "{}");
        }
        @Override public String content() { return arguments; }
    }

    record ToolResult(String callId, String toolName, String result) implements AiMessage {
        public ToolResult {
            callId = text(callId); toolName = text(toolName);
            result = Objects.requireNonNullElse(result, "");
        }
        @Override public String content() { return result; }
    }

    record Attachment(String name, String mediaType, byte[] data, Map<String, String> metadata) {
        public Attachment {
            name = text(name); mediaType = text(mediaType);
            data = data != null ? data.clone() : new byte[0];
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
        @Override public byte[] data() { return data.clone(); }
    }

    private static String text(String value) {
        return Objects.requireNonNull(value, "message text");
    }
}
