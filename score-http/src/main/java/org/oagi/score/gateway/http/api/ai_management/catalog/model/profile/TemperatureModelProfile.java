package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public interface TemperatureModelProfile extends AiModelProfile {
    CapabilityConstraint getTemperatureCapability();
    DecimalConstraint getTemperatureConstraint();
}
