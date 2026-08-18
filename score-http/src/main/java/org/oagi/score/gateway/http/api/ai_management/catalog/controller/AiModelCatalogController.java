package org.oagi.score.gateway.http.api.ai_management.catalog.controller;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelCatalogAdminService;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

import static org.oagi.score.gateway.http.common.util.Utility.separate;

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
    @GetMapping("/search") public PageResponse<AiModelCatalogView> search(
            @AuthenticationPrincipal AuthenticatedPrincipal p,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean defaultModel,
            @RequestParam(required = false) Boolean lightweightModel,
            @RequestParam(required = false) String defaultEffort,
            @RequestParam(required = false) String effort,
            @RequestParam(required = false) String updaterLoginIdList,
            @RequestParam(required = false) Instant updatedAfter,
            @RequestParam(required = false) Instant updatedBefore,
            @RequestParam(required = false) String orderBy,
            @RequestParam(required = false) Integer pageIndex,
            @RequestParam(required = false) Integer pageSize) {
        return models.search(sessions.asScoreUser(p), model, provider, enabled, defaultModel,
                lightweightModel, defaultEffort, effort, separate(updaterLoginIdList).toList(),
                updatedAfter, updatedBefore,
                org.oagi.score.gateway.http.common.util.ControllerUtils.pageRequest(
                        pageIndex, pageSize, orderBy));
    }
    @PostMapping public AiModelCatalogView create(@AuthenticationPrincipal AuthenticatedPrincipal p,
                                                   @RequestBody AiModelCatalogUpdate input) {
        return models.create(sessions.asScoreUser(p), input);
    }
    @GetMapping("/{id}") public AiModelCatalogView get(@AuthenticationPrincipal AuthenticatedPrincipal p,
                                                        @PathVariable AiModelId id) {
        return models.get(sessions.asScoreUser(p), id);
    }
    @PutMapping("/{id}") public AiModelCatalogView update(@AuthenticationPrincipal AuthenticatedPrincipal p,
                                                           @PathVariable AiModelId id,
                                                           @RequestBody AiModelCatalogUpdate input) {
        return models.update(sessions.asScoreUser(p), id, input);
    }
}
