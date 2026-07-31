package org.oagi.score.gateway.http.api.ai_management.catalog.controller;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogView;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelCatalogAdminService;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/ai/models")
public class AiModelCatalogController {
    private final AiModelCatalogAdminService models;
    private final SessionService sessions;
    public AiModelCatalogController(AiModelCatalogAdminService models, SessionService sessions) {
        this.models = models;
        this.sessions = sessions;
    }
    @GetMapping public List<AiModelCatalogView> list(@AuthenticationPrincipal AuthenticatedPrincipal p) {
        return models.list(sessions.asScoreUser(p));
    }
    @PostMapping public AiModelCatalogView create(@AuthenticationPrincipal AuthenticatedPrincipal p,
                                                   @RequestBody AiModelCatalogUpdate input) {
        return models.create(sessions.asScoreUser(p), input);
    }
    @GetMapping("/{id}") public AiModelCatalogView get(@AuthenticationPrincipal AuthenticatedPrincipal p,
                                                        @PathVariable long id) {
        return models.get(sessions.asScoreUser(p), id);
    }
    @PutMapping("/{id}") public AiModelCatalogView update(@AuthenticationPrincipal AuthenticatedPrincipal p,
                                                           @PathVariable long id,
                                                           @RequestBody AiModelCatalogUpdate input) {
        return models.update(sessions.asScoreUser(p), id, input);
    }
}
