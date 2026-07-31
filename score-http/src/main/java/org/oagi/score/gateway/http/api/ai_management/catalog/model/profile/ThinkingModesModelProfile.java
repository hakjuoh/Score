package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/** Model with selectable provider thinking modes. */
public interface ThinkingModesModelProfile extends AiModelProfile {
    List<String> getThinkingModes();
    String getDefaultThinking();
}
