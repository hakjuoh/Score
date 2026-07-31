package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public record ReasoningEffort(String name, String displayName, String description,
                              boolean defaultEffort, int sortOrder) {}
