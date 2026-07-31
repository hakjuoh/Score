package org.oagi.score.gateway.http.api.ai_management.policy.model;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

@Component
public class AiQuotaWindowCalculator {

    public AiQuotaWindow calculate(AiQuotaPeriod period, long limitTokens, Instant now) {
        if (period == null || now == null) {
            throw new IllegalArgumentException("Quota period and current time are required.");
        }
        Instant start;
        Instant end;
        if (period == AiQuotaPeriod.DAILY) {
            LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
            start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else {
            YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
            start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return new AiQuotaWindow(start, end, limitTokens);
    }
}
