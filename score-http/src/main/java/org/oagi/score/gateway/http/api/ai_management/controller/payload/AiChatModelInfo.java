package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

public record AiChatModelInfo(String name, String displayName, String provider, boolean defaultModel,
                              String description, String defaultReasoningEffort,
                              List<AiReasoningEffortInfo> reasoningEfforts,
                              Long contextWindow, Long outputReserveTokens,
                              Long autoCompactThresholdTokens, Long emergencyHeadroomTokens) {}
