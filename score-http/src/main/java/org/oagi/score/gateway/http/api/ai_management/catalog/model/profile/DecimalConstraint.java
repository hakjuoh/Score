package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

/** Decimal counterpart of {@link NumericConstraint}. */
public record DecimalConstraint(Double defaultValue, Double minimum, Double maximum,
                                boolean optional) {
    public DecimalConstraint {
        if ((minimum == null) != (maximum == null)) {
            throw new IllegalArgumentException("A decimal range requires both minimum and maximum.");
        }
        if (minimum != null && (!Double.isFinite(minimum) || !Double.isFinite(maximum)
                || minimum > maximum)) {
            throw new IllegalArgumentException("A decimal range must be finite and ordered.");
        }
        if (defaultValue != null && minimum == null) {
            throw new IllegalArgumentException("A decimal default requires a bounded range.");
        }
        if (defaultValue != null && (!Double.isFinite(defaultValue)
                || minimum != null && (defaultValue < minimum || defaultValue > maximum))) {
            throw new IllegalArgumentException("A decimal default must be finite and inside its range.");
        }
        if (!optional && defaultValue == null) {
            throw new IllegalArgumentException("A required decimal constraint needs a default.");
        }
    }
}
