package org.oagi.score.gateway.http.api.activity_management.model;

import org.oagi.score.gateway.http.common.model.Guid;

import static java.util.Objects.requireNonNull;

/** A resource involved in a SCORE activity. */
public record ScoreActivityTarget(
        String type,
        String id,
        String guid,
        String name,
        String role) {

    public static final String PRIMARY_ROLE = "PRIMARY";

    public ScoreActivityTarget {
        requireNonNull(type, "type must not be null");
        requireNonNull(id, "id must not be null");
        requireNonNull(role, "role must not be null");
    }

    public static ScoreActivityTarget primary(String type, Object id, Guid guid, String name) {
        requireNonNull(id, "id must not be null");
        return new ScoreActivityTarget(
                type,
                id.toString(),
                guid == null ? null : guid.value(),
                name,
                PRIMARY_ROLE);
    }
}
