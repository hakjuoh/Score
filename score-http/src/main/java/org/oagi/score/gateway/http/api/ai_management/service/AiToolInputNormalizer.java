package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

/** Coerces unambiguous model-produced JSON primitives to the MCP tool's declared schema. */
final class AiToolInputNormalizer {

    private static final int MAX_DEPTH = 16;
    private final ObjectMapper objectMapper;

    AiToolInputNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String normalize(String input, String schemaJson) {
        if (input == null || schemaJson == null || schemaJson.isBlank()) {
            return input;
        }
        try {
            JsonNode value = objectMapper.readTree(input);
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode normalized = normalize(value, schema, 0);
            return normalized.equals(value) ? input : objectMapper.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return input;
        }
    }

    private JsonNode normalize(JsonNode value, JsonNode schema, int depth) {
        if (value == null || schema == null || depth > MAX_DEPTH) {
            return value;
        }
        String type = schema.path("type").isTextual() ? schema.path("type").textValue() : null;
        if (value.isTextual()) {
            return normalizeText(value.textValue(), type);
        }
        if (value.isObject() && ("object".equals(type) || schema.has("properties"))) {
            ObjectNode result = ((ObjectNode) value).deepCopy();
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode current = result.get(field.getKey());
                    if (current != null) {
                        result.set(field.getKey(), normalize(current, field.getValue(), depth + 1));
                    }
                }
            }
            return result;
        }
        if (value.isArray() && ("array".equals(type) || schema.has("items"))) {
            ArrayNode result = ((ArrayNode) value).deepCopy();
            JsonNode itemSchema = schema.path("items");
            if (!itemSchema.isMissingNode()) {
                for (int index = 0; index < result.size(); index++) {
                    result.set(index, normalize(result.get(index), itemSchema, depth + 1));
                }
            }
            return result;
        }
        return value;
    }

    private JsonNode normalizeText(String value, String type) {
        String stripped = value.strip();
        try {
            if ("integer".equals(type) && stripped.matches("[-+]?\\d+")) {
                BigInteger integer = new BigInteger(stripped);
                if (integer.bitLength() < 31) {
                    return IntNode.valueOf(integer.intValue());
                }
                if (integer.bitLength() < 63) {
                    return LongNode.valueOf(integer.longValue());
                }
            }
            if ("number".equals(type)
                    && stripped.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")) {
                return DecimalNode.valueOf(new BigDecimal(stripped));
            }
            if ("boolean".equals(type)
                    && ("true".equalsIgnoreCase(stripped) || "false".equalsIgnoreCase(stripped))) {
                return BooleanNode.valueOf(Boolean.parseBoolean(stripped));
            }
        } catch (NumberFormatException ignored) {
            return objectMapper.getNodeFactory().textNode(value);
        }
        return objectMapper.getNodeFactory().textNode(value);
    }
}
