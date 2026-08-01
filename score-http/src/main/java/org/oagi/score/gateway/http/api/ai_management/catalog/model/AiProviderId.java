package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Identifies a configured AI provider. */
public record AiProviderId(BigInteger value) implements Id {

    @JsonCreator
    public static AiProviderId from(String value) {
        return new AiProviderId(new BigInteger(value));
    }

    @JsonCreator
    public static AiProviderId from(BigInteger value) {
        return new AiProviderId(value);
    }

    public static AiProviderId from(long value) {
        return new AiProviderId(BigInteger.valueOf(value));
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
