package org.oagi.score.gateway.http.api.ai_management.catalog.controller;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderApiKeyView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderConnectionTestResult;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiProviderCatalogService;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/ai/providers")
public class AiProviderCatalogController {
    private final AiProviderCatalogService providers;
    private final SessionService sessions;

    public AiProviderCatalogController(AiProviderCatalogService providers,
                                       SessionService sessions) {
        this.providers = providers;
        this.sessions = sessions;
    }

    @GetMapping
    public List<AiProviderView> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return providers.list(sessions.asScoreUser(principal));
    }

    @PostMapping
    public AiProviderView create(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                 @RequestBody AiProviderUpdate input) {
        return providers.create(sessions.asScoreUser(principal), input);
    }

    @GetMapping("/{providerId}")
    public AiProviderView get(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                              @PathVariable AiProviderId providerId) {
        return providers.get(sessions.asScoreUser(principal), providerId);
    }

    @GetMapping("/{providerId}/api-key")
    public ResponseEntity<AiProviderApiKeyView> maskedApiKey(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable AiProviderId providerId) {
        return sensitive(providers.maskedApiKey(sessions.asScoreUser(principal), providerId));
    }

    @PostMapping("/{providerId}/api-key/reveal")
    public ResponseEntity<AiProviderApiKeyView> revealApiKey(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable AiProviderId providerId) {
        return sensitive(providers.revealApiKey(sessions.asScoreUser(principal), providerId));
    }

    @GetMapping("/{providerId}/model-profiles")
    public List<AiModelProfileView> modelProfiles(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable AiProviderId providerId) {
        return providers.modelProfiles(sessions.asScoreUser(principal), providerId);
    }

    @PutMapping("/{providerId}")
    public AiProviderView update(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                 @PathVariable AiProviderId providerId,
                                 @RequestBody AiProviderUpdate input) {
        return providers.update(sessions.asScoreUser(principal), providerId, input);
    }

    @PostMapping("/{providerId}/connection-tests")
    public AiProviderConnectionTestResult testConnection(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable AiProviderId providerId,
            @RequestBody AiProviderUpdate input) {
        return providers.testConnection(sessions.asScoreUser(principal), providerId, input);
    }

    private static ResponseEntity<AiProviderApiKeyView> sensitive(AiProviderApiKeyView body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

}
