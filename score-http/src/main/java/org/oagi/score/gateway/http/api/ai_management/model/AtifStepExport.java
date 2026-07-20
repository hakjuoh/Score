package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.List;
import java.util.Map;

/** Normalized step export and its aggregate usage. */
public record AtifStepExport(
        List<Map<String, Object>> steps,
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        int collapsedUiProjections) {
}
