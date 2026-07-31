package org.oagi.score.gateway.http.api.ai_management.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Database identifier of a persisted AI chat trajectory step. */
public record AiChatStepId(BigInteger value) implements Id {

    public static final AiChatStepId NONE = new AiChatStepId(BigInteger.ZERO);

    @JsonCreator
    public static AiChatStepId from(String value) {
        return new AiChatStepId(new BigInteger(value));
    }

    @JsonCreator
    public static AiChatStepId from(BigInteger value) {
        return new AiChatStepId(value);
    }

    public static AiChatStepId from(long value) {
        return new AiChatStepId(BigInteger.valueOf(value));
    }

    public boolean isPersisted() {
        return value != null && value.signum() > 0;
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
