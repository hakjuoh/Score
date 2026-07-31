package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiCatalogModel;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelId;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiModelCatalogService;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiModelAccessMode;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUserPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.AiPolicyQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiPolicyServiceTest {

    @Test
    void missingPolicyPreservesTheCompatibilityDefaults() {
        Fixture fixture = fixture(true);
        when(fixture.policies.find(fixture.user.userId())).thenReturn(Optional.empty());

        var resolved = fixture.service.resolve(fixture.user);

        assertThat(resolved.inherited()).isTrue();
        assertThat(resolved.aiEnabled()).isTrue();
        assertThat(resolved.availableModels()).extracting(model -> model.descriptor().name())
                .containsExactly("global", "fallback");
        assertThat(resolved.defaultModelKey()).isEqualTo("global");
        assertThat(resolved.maxAgentsPerRequest()).isEqualTo(4);
        assertThat(resolved.maxActiveRequests()).isEqualTo(8);
        assertThat(resolved.maxOutputTokensPerCall()).isNull();
    }

    @Test
    void allowListIntersectsTheActiveCatalogAndFallsBackToItsFirstModel() {
        Fixture fixture = fixture(true);
        when(fixture.policies.find(fixture.user.userId())).thenReturn(Optional.of(
                new AiUserPolicy(fixture.user.userId(), true, AiModelAccessMode.ALLOW_LIST,
                        AiModelId.from(999L), true, 2, 3, 200L, 500L, null, null, 7L,
                        Set.of(AiModelId.from(2L), AiModelId.from(999L)),
                        Map.of(AiModelId.from(2L), Set.of("low")))));

        var resolved = fixture.service.resolve(fixture.user);

        assertThat(resolved.inherited()).isFalse();
        assertThat(resolved.availableModels()).extracting(model -> model.descriptor().name())
                .containsExactly("fallback");
        assertThat(resolved.defaultModelKey()).isEqualTo("fallback");
        assertThat(resolved.allowedReasoningEfforts())
                .containsEntry(AiModelId.from(2L), Set.of("low"));
    }

    @Test
    void requestSnapshotDoesNotChangeAndCannotBeReadByAnotherUser() {
        Fixture fixture = fixture(true);
        when(fixture.policies.find(fixture.user.userId())).thenReturn(Optional.empty());
        var original = fixture.service.resolve(fixture.user);
        fixture.service.snapshot("request-1", original);
        when(fixture.catalog.activeModels()).thenReturn(List.of(model(2L, "fallback", false)));

        assertThat(fixture.service.resolveSnapshot(fixture.user, "request-1"))
                .isSameAs(original);
        ScoreUser another = user(2L);
        assertThatThrownBy(() -> fixture.service.resolveSnapshot(another, "request-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static Fixture fixture(boolean policyEnabled) {
        AiPolicyQueryRepository policies = mock(AiPolicyQueryRepository.class);
        AiModelCatalogService catalog = mock(AiModelCatalogService.class);
        List<AiCatalogModel> models = List.of(model(1L, "global", true),
                model(2L, "fallback", false));
        when(catalog.activeModels()).thenReturn(models);
        when(catalog.defaultModelKey()).thenReturn("global");
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getPolicy().setEnabled(policyEnabled);
        return new Fixture(policies, catalog, user(1L),
                new AiPolicyService(policies, catalog, properties));
    }

    private static ScoreUser user(long id) {
        return new ScoreUser(new UserId(BigInteger.valueOf(id)), "user" + id,
                "User " + id, null, false, List.of(ScoreRole.END_USER));
    }

    private static AiCatalogModel model(long id, String key, boolean defaultModel) {
        return new AiCatalogModel(AiModelId.from(id), new ScoreAiModelRegistry.ModelDescriptor(
                key, key, "", "provider", defaultModel, "medium",
                List.of(new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                        "low", "Low", "")),
                new ScoreAiModelRegistry.ContextBudgetDescriptor(
                        1000L, 100L, 800L, 100L, 500L, true)));
    }

    private record Fixture(AiPolicyQueryRepository policies, AiModelCatalogService catalog,
                           ScoreUser user, AiPolicyService service) {
    }
}
