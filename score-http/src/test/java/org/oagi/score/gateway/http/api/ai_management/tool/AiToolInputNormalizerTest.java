package org.oagi.score.gateway.http.api.ai_management.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolInputNormalizerTest {

    private final AiToolInputNormalizer normalizer = new AiToolInputNormalizer(new ObjectMapper());

    @Test
    void coercesUnambiguousModelPrimitivesUsingNestedToolSchema() {
        String normalized = normalizer.normalize("""
                {"biz_ctx_id":"18","include_values":"true","nested":{"count":"2"},
                 "ids":["1","2"],"name":"018"}
                """, """
                {"type":"object","properties":{
                  "biz_ctx_id":{"type":"integer"},
                  "include_values":{"type":"boolean"},
                  "nested":{"type":"object","properties":{"count":{"type":"number"}}},
                  "ids":{"type":"array","items":{"type":"integer"}},
                  "name":{"type":"string"}
                }}
                """);

        assertThat(normalized).isEqualTo(
                "{\"biz_ctx_id\":18,\"include_values\":true,\"nested\":{\"count\":2},"
                        + "\"ids\":[1,2],\"name\":\"018\"}");
    }

    @Test
    void leavesAmbiguousOrInvalidValuesUntouchedForNormalSchemaValidation() {
        assertThat(normalizer.normalize("{\"id\":\"18x\"}", """
                {"type":"object","properties":{"id":{"type":"integer"}}}
                """)).isEqualTo("{\"id\":\"18x\"}");
        assertThat(normalizer.normalize("not-json", "{\"type\":\"object\"}"))
                .isEqualTo("not-json");
    }
}
