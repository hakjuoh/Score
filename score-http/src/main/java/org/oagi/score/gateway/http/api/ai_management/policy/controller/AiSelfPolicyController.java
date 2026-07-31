package org.oagi.score.gateway.http.api.ai_management.policy.controller;

import org.oagi.score.gateway.http.api.ai_management.policy.model.AiSelfPolicyView;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/policy")
public class AiSelfPolicyController {

    private final AiAdminPolicyService policies;
    private final SessionService sessions;

    public AiSelfPolicyController(AiAdminPolicyService policies, SessionService sessions) {
        this.policies = policies;
        this.sessions = sessions;
    }

    @GetMapping("/me")
    public AiSelfPolicyView me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return AiSelfPolicyView.from(policies.self(sessions.asScoreUser(principal)));
    }
}
