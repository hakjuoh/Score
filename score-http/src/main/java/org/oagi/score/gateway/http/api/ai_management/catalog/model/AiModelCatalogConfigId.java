package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.oagi.score.gateway.http.common.model.Id;

import java.math.BigInteger;

/** Identifier of the singleton AI model-catalog configuration record. */
public record AiModelCatalogConfigId(BigInteger value) implements Id {

    public static final AiModelCatalogConfigId GLOBAL =
            new AiModelCatalogConfigId(BigInteger.ONE);

    @JsonCreator
    public static AiModelCatalogConfigId from(String value) {
        return new AiModelCatalogConfigId(new BigInteger(value));
    }

    @JsonCreator
    public static AiModelCatalogConfigId from(BigInteger value) {
        return new AiModelCatalogConfigId(value);
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }
}
