package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

/** An optional value uses {@code null} to reset to its profile default. */
public record NumericConstraint(Long defaultValue, Long minimum, Long maximum,
                                boolean optional) {
    public NumericConstraint {
        if ((minimum == null) != (maximum == null)) {
            throw new IllegalArgumentException("A numeric range requires both minimum and maximum.");
        }
        if (minimum != null && minimum > maximum) {
            throw new IllegalArgumentException("A numeric minimum cannot exceed its maximum.");
        }
        if (defaultValue != null && minimum == null) {
            throw new IllegalArgumentException("A numeric default requires a bounded range.");
        }
        if (defaultValue != null && minimum != null
                && (defaultValue < minimum || defaultValue > maximum)) {
            throw new IllegalArgumentException("A numeric default must be inside its range.");
        }
        if (!optional && defaultValue == null) {
            throw new IllegalArgumentException("A required numeric constraint needs a default.");
        }
    }
}
