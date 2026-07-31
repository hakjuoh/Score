package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public record CapabilityConstraint(boolean supported, boolean defaultEnabled) {
    public static final CapabilityConstraint UNSUPPORTED = new CapabilityConstraint(false, false);

    public CapabilityConstraint {
        if (defaultEnabled && !supported) {
            throw new IllegalArgumentException("An unsupported capability cannot be enabled by default.");
        }
    }
}
