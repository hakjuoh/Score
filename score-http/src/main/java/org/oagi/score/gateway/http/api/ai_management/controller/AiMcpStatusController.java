package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.model.AiMcpStatus;
import org.oagi.score.gateway.http.api.ai_management.service.AiMcpStatusService;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/chat")
public class AiMcpStatusController {

    private final AiMcpStatusService mcpStatus;
    private final SessionService sessionService;

    public AiMcpStatusController(AiMcpStatusService mcpStatus, SessionService sessionService) {
        this.mcpStatus = mcpStatus;
        this.sessionService = sessionService;
    }

    @GetMapping("/mcp")
    public AiMcpStatus status(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return mcpStatus.check(sessionService.asScoreUser(principal));
    }
}
