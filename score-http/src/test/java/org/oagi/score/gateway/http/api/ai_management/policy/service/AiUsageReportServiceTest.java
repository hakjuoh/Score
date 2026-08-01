package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.Sort;
import org.oagi.score.gateway.http.common.model.SortDirection;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiUsageReportServiceTest {

    @Test
    void defaultsToNewestCallsAndUsesCallIdAsAStableTieBreaker() {
        var order = AiUsageReportService.order(new PageRequest());

        assertThat(order).extracting(field -> field.$field().getName())
                .containsExactly("reserved_timestamp", "call_id");
        assertThat(order).extracting(field -> field.getOrder())
                .containsExactly(SortOrder.DESC, SortOrder.DESC);
    }

    @Test
    void mapsSupportedTableSortsAndKeepsTheStableTieBreaker() {
        var page = new PageRequest(0, 25,
                List.of(new Sort("model", SortDirection.ASC)));

        var order = AiUsageReportService.order(page);

        assertThat(order).extracting(field -> field.$field().getName())
                .containsExactly("model_key", "call_id");
        assertThat(order).extracting(field -> field.getOrder())
                .containsExactly(SortOrder.ASC, SortOrder.DESC);
    }

    @Test
    void rejectsAReversedUsagePeriodBeforeQuerying() {
        var service = new AiUsageReportService(mock(DSLContext.class));

        assertThatThrownBy(() -> service.load(
                new UserId(BigInteger.ONE),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                new PageRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Usage end must be on or after usage start.");
    }
}
