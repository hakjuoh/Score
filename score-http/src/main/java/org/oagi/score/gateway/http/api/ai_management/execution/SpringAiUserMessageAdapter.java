package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Lossless Spring AI conversion for user text and policy-visible attachments. */
public final class SpringAiUserMessageAdapter {

    private SpringAiUserMessageAdapter() {
    }

    public static AiMessage.User toCore(UserMessage message) {
        Objects.requireNonNull(message, "message");
        return new AiMessage.User(Objects.requireNonNullElse(message.getText(), ""),
                message.getMedia().stream().map(SpringAiUserMessageAdapter::toCore).toList());
    }

    public static UserMessage toSpring(AiMessage.User message) {
        Objects.requireNonNull(message, "message");
        return UserMessage.builder().text(message.content())
                .media(message.attachments().stream()
                        .map(SpringAiUserMessageAdapter::toSpring).toList())
                .build();
    }

    private static AiMessage.Attachment toCore(Media media) {
        Objects.requireNonNull(media, "media");
        byte[] data = Objects.requireNonNull(media.getDataAsByteArray(), "media data");
        String name = StringUtils.hasText(media.getName()) ? media.getName()
                : StringUtils.hasText(media.getId()) ? media.getId() : "attachment";
        Map<String, String> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(media.getId())) metadata.put("id", media.getId());
        return new AiMessage.Attachment(name,
                Objects.requireNonNull(media.getMimeType(), "media type").toString(),
                data, Map.copyOf(metadata));
    }

    private static Media toSpring(AiMessage.Attachment attachment) {
        Media.Builder builder = Media.builder()
                .mimeType(MimeType.valueOf(attachment.mediaType()))
                .data(attachment.data())
                .name(attachment.name());
        String id = attachment.metadata().get("id");
        if (StringUtils.hasText(id)) builder.id(id);
        return builder.build();
    }
}
