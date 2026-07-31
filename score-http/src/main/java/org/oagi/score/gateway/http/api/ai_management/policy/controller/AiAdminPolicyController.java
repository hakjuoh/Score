package org.oagi.score.gateway.http.api.ai_management.policy.controller;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelCatalogService;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUpdate;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUserSummary;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyView;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiAdminUsageView;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiQuotaAdjustmentRequest;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/ai")
public class AiAdminPolicyController {

    private final AiAdminPolicyService policies;
    private final AiModelCatalogService catalog;
    private final SessionService sessions;

    public AiAdminPolicyController(AiAdminPolicyService policies,
                                   AiModelCatalogService catalog,
                                   SessionService sessions) {
        this.policies = policies;
        this.catalog = catalog;
        this.sessions = sessions;
    }

    @GetMapping("/users")
    public List<AiPolicyUserSummary> users(
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return policies.users(sessions.asScoreUser(principal));
    }

    @GetMapping("/users/{userId}/policy")
    public AiPolicyView policy(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                               @PathVariable UserId userId) {
        return policies.get(sessions.asScoreUser(principal), userId);
    }

    @PutMapping("/users/{userId}/policy")
    public AiPolicyView update(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                               @PathVariable UserId userId,
                               @RequestBody AiPolicyUpdate update) {
        return policies.update(sessions.asScoreUser(principal), userId, update);
    }

    @DeleteMapping("/users/{userId}/policy")
    public void delete(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                       @PathVariable UserId userId,
                       @RequestParam long expectedVersion) {
        policies.delete(sessions.asScoreUser(principal), userId, expectedVersion);
    }

    @GetMapping("/users/{userId}/usage")
    public AiAdminUsageView usage(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                  @PathVariable UserId userId) {
        return policies.usage(sessions.asScoreUser(principal), userId);
    }

    @org.springframework.web.bind.annotation.PostMapping(
            "/users/{userId}/quota-adjustments")
    public AiAdminUsageView adjustQuota(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UserId userId,
            @RequestBody AiQuotaAdjustmentRequest input) {
        return policies.adjustQuota(sessions.asScoreUser(principal), userId, input);
    }

    @org.springframework.web.bind.annotation.PostMapping(
            "/users/{userId}/cancel-active-requests")
    public java.util.Map<String, Integer> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UserId userId) {
        return java.util.Map.of("cancelledRequests",
                policies.cancelActiveRequests(sessions.asScoreUser(principal), userId));
    }

}
