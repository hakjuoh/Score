package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.List;
import java.util.Map;

/** Result of normalizing stored steps for trajectory export. */
public record AtifNormalizationResult(
        List<Map<String, Object>> steps,
        int collapsedUiProjections) {
}
