package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;

/** Tool callbacks and approval state prepared for one provider conversation. */
record AiChatToolSetup(AiChangeToolGuard.GuardedToolSession guardedSession,
                       org.springframework.ai.tool.ToolCallbackProvider executableTools,
                       String directToolCatalog) {

    static AiChatToolSetup empty() {
        return new AiChatToolSetup(null, null, "");
    }
}
