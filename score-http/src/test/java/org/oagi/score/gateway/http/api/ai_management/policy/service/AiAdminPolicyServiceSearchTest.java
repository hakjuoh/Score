package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.AiAdminPage;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiPolicyUserSummary;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.Sort;
import org.oagi.score.gateway.http.common.model.SortDirection;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAdminPolicyServiceSearchTest {

    @Test
    void ordersUpdatedPoliciesNewestFirstAndInheritedPoliciesLast() {
        var inherited = summary("inherited", null);
        var older = summary("older", Instant.parse("2026-07-01T00:00:00Z"));
        var newer = summary("newer", Instant.parse("2026-07-31T00:00:00Z"));
        var request = new PageRequest(0, 10,
                List.of(new Sort("updatedOn", SortDirection.DESC)));

        var response = AiAdminPage.of(List.of(inherited, older, newer).stream(), request,
                AiAdminPolicyService::calculatedComparator,
                AiAdminPolicyService.defaultCalculatedOrder());

        assertThat(response.getList()).extracting(AiPolicyUserSummary::loginId)
                .containsExactly("newer", "older", "inherited");
    }

    @Test
    void usesUpdatedOnDescendingAsTheCalculatedPathDefault() {
        var inherited = summary("inherited", null);
        var older = summary("older", Instant.parse("2026-07-01T00:00:00Z"));
        var newer = summary("newer", Instant.parse("2026-07-31T00:00:00Z"));

        var response = AiAdminPage.of(List.of(inherited, older, newer).stream(),
                new PageRequest(0, 10, List.of()),
                AiAdminPolicyService::calculatedComparator,
                AiAdminPolicyService.defaultCalculatedOrder());

        assertThat(response.getList()).extracting(AiPolicyUserSummary::loginId)
                .containsExactly("newer", "older", "inherited");
    }

    private AiPolicyUserSummary summary(String loginId, Instant updatedOn) {
        return new AiPolicyUserSummary(loginId, loginId, loginId, null,
                updatedOn == null, true, true, 0, null, 0, 0,
                null, 0, updatedOn != null ? "admin" : null, updatedOn);
    }
}
