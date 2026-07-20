package org.oagi.score.gateway.http.api.ai_management.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Serializes JSON values stored by the AI chat repositories.
 */
public final class AiChatJsonSerializer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE =
            new TypeReference<>() {};

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AiChatJsonSerializer INSTANCE = new AiChatJsonSerializer();

    private AiChatJsonSerializer() {
    }

    public static AiChatJsonSerializer getInstance() {
        return INSTANCE;
    }

    public String serialize(Object value) {
        if (value == null || value instanceof Map<?, ?> map && map.isEmpty()
                || value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize AI chat data.", exception);
        }
    }

    public Map<String, Object> deserializeMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored AI chat data is invalid.", exception);
        }
    }

    public Map<String, Object> deserializeMapOrEmpty(String json) {
        Map<String, Object> value = deserializeMap(json);
        return value != null ? Map.copyOf(value) : Map.of();
    }

    public List<Map<String, Object>> deserializeListOfMaps(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, LIST_OF_MAPS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored AI chat data is invalid.", exception);
        }
    }
}
