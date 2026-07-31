package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiQuotaWindowCalculatorTest {

    private final AiQuotaWindowCalculator calculator = new AiQuotaWindowCalculator();

    @Test
    void calculatesDailyWindowsInUtc() {
        AiQuotaWindow window = calculator.calculate(AiQuotaPeriod.DAILY, 100,
                Instant.parse("2026-07-31T23:59:59Z"));
        assertThat(window.start()).isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void calculatesMonthlyWindowsAcrossYearBoundary() {
        AiQuotaWindow window = calculator.calculate(AiQuotaPeriod.MONTHLY, 100,
                Instant.parse("2026-12-31T23:59:59Z"));
        assertThat(window.start()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }
}
