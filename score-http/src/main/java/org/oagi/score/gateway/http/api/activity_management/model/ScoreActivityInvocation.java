package org.oagi.score.gateway.http.api.activity_management.model;

import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Immutable method inputs exposed to an activity handler. */
public record ScoreActivityInvocation(
        String category,
        String action,
        String operation,
        List<Object> arguments) {

    public ScoreActivityInvocation {
        requireNonNull(category, "category must not be null");
        requireNonNull(action, "action must not be null");
        requireNonNull(operation, "operation must not be null");
        arguments = Collections.unmodifiableList(new ArrayList<>(
                requireNonNull(arguments, "arguments must not be null")));
    }

    public ScoreActivityInvocation(String category, String action, List<Object> arguments) {
        this(category, action, "", arguments);
    }

    public String name() {
        return category + "." + action;
    }

    @Nullable
    public <T> T argument(int index, Class<T> type) {
        requireNonNull(type, "type must not be null");
        Object value = arguments.get(index);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Activity argument " + index + " is not a " + type.getSimpleName());
        }
        return type.cast(value);
    }
}
