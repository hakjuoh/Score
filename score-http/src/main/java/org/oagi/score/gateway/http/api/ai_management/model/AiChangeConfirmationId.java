package org.oagi.score.gateway.http.api.ai_management.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Database identifier of an AI change-confirmation record. */
public record AiChangeConfirmationId(BigInteger value) implements Id {

    @JsonCreator
    public static AiChangeConfirmationId from(String value) {
        return new AiChangeConfirmationId(new BigInteger(value));
    }

    @JsonCreator
    public static AiChangeConfirmationId from(BigInteger value) {
        return new AiChangeConfirmationId(value);
    }

    public static AiChangeConfirmationId from(long value) {
        return new AiChangeConfirmationId(BigInteger.valueOf(value));
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
