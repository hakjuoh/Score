package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A typed Spring AI chat option exposed by a model profile.
 * The value is the application default; {@code null} delegates to the provider default.
 */
public record AiModelOption(String key, String type, Object value, String description,
                            List<String> allowedValues) {
    private static final Set<String> TYPES = Set.of(
            "boolean", "integer", "decimal", "string", "json", "enum");

    public AiModelOption {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("An option key is required.");
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported option type: " + type);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("An option description is required.");
        }
        allowedValues = allowedValues != null ? List.copyOf(allowedValues) : List.of();
        if ("enum".equals(type) && allowedValues.isEmpty()) {
            throw new IllegalArgumentException("An enum option requires allowed values.");
        }
        if (!"enum".equals(type) && !allowedValues.isEmpty()) {
            throw new IllegalArgumentException("Only enum options can have allowed values.");
        }
        if (value != null && !validValue(type, value, allowedValues)) {
            throw new IllegalArgumentException("The value for " + key + " does not match " + type + ".");
        }
    }

    private static boolean validValue(String type, Object value, List<String> allowedValues) {
        return switch (type) {
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "decimal" -> value instanceof Number;
            case "string" -> value instanceof String;
            case "json" -> value instanceof Map<?, ?> || value instanceof List<?>
                    || value instanceof String || value instanceof Number || value instanceof Boolean;
            case "enum" -> value instanceof String text && allowedValues.contains(text);
            default -> false;
        };
    }
}
