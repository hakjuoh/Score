package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelCatalogService;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiModelAccessMode;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUserPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.AiPolicyQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Service;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiPolicyService {

    private final AiPolicyQueryRepository policies;
    private final AiModelCatalogService catalog;
    private final ConcurrentHashMap<String, EffectiveAiPolicy> requestSnapshots =
            new ConcurrentHashMap<>();
    private final boolean enabled;

    public AiPolicyService(AiPolicyQueryRepository policies, AiModelCatalogService catalog,
                           ScoreAiProperties properties) {
        this.policies = policies;
        this.catalog = catalog;
        this.enabled = properties.getPolicy().isEnabled();
    }

    public EffectiveAiPolicy resolve(ScoreUser requester) {
        if (requester == null || requester.userId() == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Authentication is required to resolve an AI policy.");
        }
        List<AiCatalogModel> active = catalog.activeModels();
        AiUserPolicy stored = enabled ? policies.find(requester.userId()).orElse(null) : null;
        if (stored == null) {
            return new EffectiveAiPolicy(requester.userId(), true, true, active,
                    catalog.defaultModelKey(), true, 4, 8, null, null,
                    null, null, 0L, Map.of());
        }

        List<AiCatalogModel> allowed = stored.modelAccessMode() == AiModelAccessMode.ALL
                ? active : active.stream()
                .filter(model -> stored.allowedModels().contains(model.id())).toList();
        String defaultKey = stored.defaultModelId() != null
                ? allowed.stream().filter(model -> model.id().equals(stored.defaultModelId()))
                .map(model -> model.descriptor().name()).findFirst().orElse(null) : null;
        if (defaultKey == null && allowed.stream().anyMatch(model ->
                model.descriptor().name().equals(catalog.defaultModelKey()))) {
            defaultKey = catalog.defaultModelKey();
        }
        if (defaultKey == null && !allowed.isEmpty()) {
            defaultKey = allowed.getFirst().descriptor().name();
        }
        return new EffectiveAiPolicy(requester.userId(), false, stored.aiEnabled(), allowed,
                defaultKey, stored.multiAgentEnabled(), stored.maxAgentsPerRequest(),
                stored.maxActiveRequests(), stored.maxOutputTokensPerCall(),
                stored.maxTotalTokensPerRequest(), stored.quotaPeriod(), stored.quotaTokens(),
                stored.policyVersion(), stored.allowedReasoningEfforts());
    }

    public void snapshot(String requestId, EffectiveAiPolicy policy) {
        if (requestId == null || requestId.isBlank() || policy == null) return;
        EffectiveAiPolicy existing = requestSnapshots.putIfAbsent(requestId, policy);
        if (existing != null && !existing.equals(policy)) {
            throw new IllegalStateException("The AI request already has a different policy snapshot.");
        }
    }

    public EffectiveAiPolicy resolveSnapshot(ScoreUser requester, String requestId) {
        EffectiveAiPolicy snapshot = requestSnapshots.get(requestId);
        if (snapshot == null) {
            snapshot = resolve(requester);
            snapshot(requestId, snapshot);
        }
        if (!snapshot.userId().equals(requester.userId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "The AI policy snapshot belongs to a different user.");
        }
        return snapshot;
    }

    public void clearSnapshot(String requestId) {
        if (requestId != null) requestSnapshots.remove(requestId);
    }
}
