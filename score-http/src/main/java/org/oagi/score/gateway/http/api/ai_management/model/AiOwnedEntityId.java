package org.oagi.score.gateway.http.api.ai_management.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Identifier of the owner-scoped entity targeted by an AI tool call. */
public record AiOwnedEntityId(BigInteger value) implements Id {

    @JsonCreator
    public static AiOwnedEntityId from(String value) {
        return new AiOwnedEntityId(new BigInteger(value));
    }

    @JsonCreator
    public static AiOwnedEntityId from(BigInteger value) {
        return new AiOwnedEntityId(value);
    }

    public static AiOwnedEntityId from(long value) {
        return new AiOwnedEntityId(BigInteger.valueOf(value));
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
