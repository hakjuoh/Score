package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.Objects;

/** Context and output budgets common to every model. */
public record ModelTokenConstraints(NumericConstraint contextWindow,
                                    NumericConstraint maxOutputTokens,
                                    NumericConstraint outputReserveTokens,
                                    NumericConstraint autoCompactThresholdTokens,
                                    NumericConstraint emergencyHeadroomTokens,
                                    NumericConstraint toolOutputTokenLimit) {

    public ModelTokenConstraints {
        Objects.requireNonNull(contextWindow, "contextWindow");
        Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        Objects.requireNonNull(outputReserveTokens, "outputReserveTokens");
        Objects.requireNonNull(autoCompactThresholdTokens, "autoCompactThresholdTokens");
        Objects.requireNonNull(emergencyHeadroomTokens, "emergencyHeadroomTokens");
        Objects.requireNonNull(toolOutputTokenLimit, "toolOutputTokenLimit");
        requireBounded("contextWindow", contextWindow);
        requireBounded("maxOutputTokens", maxOutputTokens);
        requireBounded("outputReserveTokens", outputReserveTokens);
        requireBounded("autoCompactThresholdTokens", autoCompactThresholdTokens);
        requireBounded("emergencyHeadroomTokens", emergencyHeadroomTokens);
        requireBounded("toolOutputTokenLimit", toolOutputTokenLimit);
    }

    public static ModelTokenConstraints standard(long contextWindow, long maxOutputTokens,
                                                  long outputReserveTokens,
                                                  long autoCompactThresholdTokens) {
        return new ModelTokenConstraints(
                new NumericConstraint(contextWindow, 1L, contextWindow, false),
                new NumericConstraint(maxOutputTokens, 1L, maxOutputTokens, true),
                new NumericConstraint(outputReserveTokens, 1L, contextWindow - 1L, true),
                new NumericConstraint(autoCompactThresholdTokens, 1L, contextWindow - 1L, true),
                new NumericConstraint(8_192L, 0L, contextWindow - 1L, false),
                new NumericConstraint(32_000L, 1L, contextWindow - 1L, false));
    }

    private static void requireBounded(String name, NumericConstraint constraint) {
        if (constraint.defaultValue() == null
                || constraint.minimum() == null || constraint.maximum() == null) {
            throw new IllegalArgumentException(name + " requires a bounded default value.");
        }
    }
}
