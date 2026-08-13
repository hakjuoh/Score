package org.oagi.score.gateway.http.api.activity_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreActivityEventFactoryTest {

    @Test
    void createsTheCommonEnvelopeAndCopiesTraceCorrelation() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        ScoreActivityEventFactory factory = new ScoreActivityEventFactory(
                Clock.fixed(now, ZoneOffset.UTC),
                () -> new ScoreActivityContext(
                        "1234567890abcdef1234567890abcdef", "1234567890abcdef",
                        "RELEASE_DRAFT", "request-1", now.toString()));
        ScoreUser requester = new ScoreUser(
                new UserId(BigInteger.valueOf(7)),
                "developer",
                "Developer",
                null,
                false,
                List.of(ScoreRole.DEVELOPER));

        ScoreActivityEvent event = factory.succeeded(
                "ui.page.view",
                "SCORE_HTTP_API",
                requester,
                List.of(new ScoreActivityTarget("PAGE", "/core_component/acc/42", null, null, "PRIMARY")),
                Map.of("route", "/core_component/acc/42"));

        assertThat(event.schemaVersion()).isEqualTo("1.0");
        assertThat(event.occurredAt()).isEqualTo(now);
        assertThat(event.name()).isEqualTo("ui.page.view");
        assertThat(event.actor().userId()).isEqualTo("7");
        assertThat(event.actor().loginId()).isEqualTo("developer");
        assertThat(event.context()).isEqualTo(new ScoreActivityContext(
                "1234567890abcdef1234567890abcdef", "1234567890abcdef",
                "RELEASE_DRAFT", "request-1", now.toString()));
        assertThat(event.eventId()).isNotBlank();
    }

    @Test
    void createsAFailedEnvelopeWithoutRequiringTraceCorrelation() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        ScoreActivityEventFactory factory = new ScoreActivityEventFactory(
                Clock.fixed(now, ZoneOffset.UTC),
                () -> {
                    throw new IllegalStateException("tracing unavailable");
                });
        ScoreUser requester = new ScoreUser(
                new UserId(BigInteger.valueOf(7)),
                "developer",
                "Developer",
                null,
                false,
                List.of(ScoreRole.DEVELOPER));

        ScoreActivityEvent event = factory.failed(
                "acc.update",
                "SCORE_HTTP_API",
                requester,
                List.of(new ScoreActivityTarget("ACC", "42", null, null, "PRIMARY")),
                Map.of("errorCode", "INTERNAL_ERROR"));

        assertThat(event.outcome()).isEqualTo("FAILED");
        assertThat(event.properties()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(event.context()).isEqualTo(ScoreActivityContext.empty());
    }
}
