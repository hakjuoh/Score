package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

public interface ReasoningEffortModelProfile extends AiModelProfile {
    List<ReasoningEffort> getReasoningEfforts();
}
