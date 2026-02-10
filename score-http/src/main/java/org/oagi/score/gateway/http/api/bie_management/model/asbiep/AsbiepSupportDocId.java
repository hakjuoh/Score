package org.oagi.score.gateway.http.api.bie_management.model.asbiep;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/**
 * Represents a unique identifier for an ASBIEP Supporting Documentation.
 * Implements the {@link Id} interface to provide a standardized way
 * to retrieve the identifier value.
 */
public record AsbiepSupportDocId(BigInteger value) implements Id {

    @JsonCreator
    public static AsbiepSupportDocId from(String value) {
        return new AsbiepSupportDocId(new BigInteger(value));
    }

    @JsonCreator
    public static AsbiepSupportDocId from(BigInteger value) {
        return new AsbiepSupportDocId(value);
    }

    @JsonValue
    public BigInteger value() {
        return value;
    }

    public String toString() {
        return (value() != null) ? value().toString() : null;
    }

}