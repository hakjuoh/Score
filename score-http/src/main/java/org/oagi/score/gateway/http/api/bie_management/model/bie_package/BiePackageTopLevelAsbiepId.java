package org.oagi.score.gateway.http.api.bie_management.model.bie_package;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/**
 * Represents a unique identifier for a BIE package - Top Level Asbiep relationship record.
 * Implements the {@link Id} interface to provide a standardized way
 * to retrieve the identifier value.
 */
public record BiePackageTopLevelAsbiepId(BigInteger value) implements Id {

    @JsonCreator
    public static BiePackageTopLevelAsbiepId from(String value) {
        return new BiePackageTopLevelAsbiepId(new BigInteger(value));
    }

    @JsonCreator
    public static BiePackageTopLevelAsbiepId from(BigInteger value) {
        return new BiePackageTopLevelAsbiepId(value);
    }

    public String toString() {
        return (value() != null) ? value().toString() : null;
    }

}
