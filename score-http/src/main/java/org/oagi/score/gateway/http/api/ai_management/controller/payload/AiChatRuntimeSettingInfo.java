package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.List;

public record AiChatRuntimeSettingInfo(String name, String displayName, String description,
                                       String type, Object defaultValue,
                                       List<AiChatRuntimeSettingOption> options,
                                       Number minimum, Number maximum, Number step) {
    public AiChatRuntimeSettingInfo {
        options = options != null ? List.copyOf(options) : List.of();
    }
}
