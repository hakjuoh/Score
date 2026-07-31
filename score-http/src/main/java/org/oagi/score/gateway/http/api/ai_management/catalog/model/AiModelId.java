package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Identifies a persisted AI model catalog entry. */
public record AiModelId(BigInteger value) implements Id {

    @JsonCreator
    public static AiModelId from(String value) {
        return new AiModelId(new BigInteger(value));
    }

    @JsonCreator
    public static AiModelId from(BigInteger value) {
        return new AiModelId(value);
    }

    public static AiModelId from(long value) {
        return new AiModelId(BigInteger.valueOf(value));
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
