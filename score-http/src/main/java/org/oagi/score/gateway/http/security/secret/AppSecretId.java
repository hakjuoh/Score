package org.oagi.score.gateway.http.security.secret;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Identifies an encrypted application secret. */
public record AppSecretId(BigInteger value) implements Id {

    @JsonCreator
    public static AppSecretId from(String value) {
        return new AppSecretId(new BigInteger(value));
    }

    @JsonCreator
    public static AppSecretId from(BigInteger value) {
        return new AppSecretId(value);
    }

    public static AppSecretId from(long value) {
        return new AppSecretId(BigInteger.valueOf(value));
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
