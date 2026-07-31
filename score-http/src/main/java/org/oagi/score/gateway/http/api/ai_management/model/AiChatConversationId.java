package org.oagi.score.gateway.http.api.ai_management.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Internal database identifier of an AI chat conversation. */
public record AiChatConversationId(BigInteger value) implements Id {

    @JsonCreator
    public static AiChatConversationId from(String value) {
        return new AiChatConversationId(new BigInteger(value));
    }

    @JsonCreator
    public static AiChatConversationId from(BigInteger value) {
        return new AiChatConversationId(value);
    }

    public static AiChatConversationId from(long value) {
        return new AiChatConversationId(BigInteger.valueOf(value));
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
