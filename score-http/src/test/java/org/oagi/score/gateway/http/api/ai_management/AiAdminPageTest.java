package org.oagi.score.gateway.http.api.ai_management;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.Sort;
import org.oagi.score.gateway.http.common.model.SortDirection;

import java.util.Comparator;
import java.util.List;
import org.jooq.impl.DSL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAdminPageTest {

    @Test
    void appliesRequestedSortAndReturnsOnlyTheRequestedPage() {
        PageRequest request = new PageRequest(1, 2,
                List.of(new Sort("name", SortDirection.DESC)));

        var response = AiAdminPage.of(List.of("alpha", "delta", "bravo", "charlie").stream(),
                request, sort -> "name".equals(sort.field())
                        ? Comparator.naturalOrder() : null, Comparator.naturalOrder());

        assertThat(response.getList()).containsExactly("bravo", "alpha");
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(2);
        assertThat(response.getLength()).isEqualTo(4);
    }

    @Test
    void matchesTextCaseInsensitivelyAndRejectsUnsafePageSizes() {
        assertThat(AiAdminPage.contains("Anthropic Provider", "  PROVIDER ")).isTrue();
        assertThat(AiAdminPage.contains(null, "provider")).isFalse();

        PageRequest oversized = new PageRequest(0, 101,
                List.of(new Sort("name", SortDirection.ASC)));
        assertThatThrownBy(() -> AiAdminPage.of(List.of("one").stream(), oversized,
                ignored -> Comparator.naturalOrder(), Comparator.naturalOrder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");

        PageRequest distantPage = new PageRequest(Integer.MAX_VALUE, 100, List.of());
        assertThat(AiAdminPage.of(List.of("one").stream(), distantPage,
                ignored -> null, Comparator.naturalOrder()).getList()).isEmpty();
    }

    @Test
    void buildsIncludedAndExcludedUpdaterSelections() {
        var loginId = DSL.field(DSL.name("updater", "login_id"), String.class);

        String sql = DSL.using(org.jooq.SQLDialect.MARIADB)
                .renderInlined(AiAdminPage.loginIdSelection(
                        loginId, List.of("alice", "!bob")));

        assertThat(sql).contains("`updater`.`login_id` in ('alice')")
                .contains("`updater`.`login_id` not in ('bob')")
                .contains("`updater`.`login_id` is null");
        assertThat(DSL.using(org.jooq.SQLDialect.MARIADB).renderInlined(
                AiAdminPage.loginIdSelection(loginId, List.of()))).isEqualTo("true");
    }
}
