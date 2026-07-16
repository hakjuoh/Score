package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

public record AiChatRuntimeInfo(String name, String displayName, String description,
                                List<AiChatRuntimeSettingInfo> settings) {
    public AiChatRuntimeInfo(String name, String displayName, String description) {
        this(name, displayName, description, List.of());
    }

    public AiChatRuntimeInfo {
        settings = settings != null ? List.copyOf(settings) : List.of();
    }
}
