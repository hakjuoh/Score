package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

public interface OutputEffortModelProfile extends AiModelProfile {
    CapabilityConstraint getOutputEffortCapability();
    String getDefaultOutputEffort();
    List<ReasoningEffort> getOutputEfforts();
}
