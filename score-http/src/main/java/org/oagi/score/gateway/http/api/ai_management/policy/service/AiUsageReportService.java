package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.AiAdminPage;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiAdminUsageView;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_TOKEN_USAGE_LEDGER;

@Service
public class AiUsageReportService {

    private final DSLContext dsl;

    public AiUsageReportService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public PeriodPage load(UserId userId, Instant start, Instant end, PageRequest pageRequest) {
        AiAdminPage.validate(pageRequest);
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("Usage end must be on or after usage start.");
        }

        Condition condition = AI_TOKEN_USAGE_LEDGER.APP_USER_ID.eq(
                ULong.valueOf(userId.value()));
        if (start != null) {
            condition = condition.and(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.ge(
                    start.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        if (end != null) {
            condition = condition.and(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.lt(
                    end.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }

        int total = dsl.fetchCount(DSL.selectOne().from(AI_TOKEN_USAGE_LEDGER).where(condition));
        Field<Long> chargedTokens = DSL.coalesce(DSL.sum(
                AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS.cast(Long.class)).cast(Long.class), 0L);
        Field<Long> reservedTokens = DSL.coalesce(DSL.sum(DSL.when(
                AI_TOKEN_USAGE_LEDGER.STATUS.eq("RESERVED"),
                AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS.cast(Long.class)).otherwise(0L))
                .cast(Long.class), 0L);
        var totals = dsl.select(chargedTokens, reservedTokens)
                .from(AI_TOKEN_USAGE_LEDGER).where(condition).fetchOne();

        List<SortField<?>> order = order(pageRequest);
        long offset = AiAdminPage.offset(pageRequest);
        List<AiAdminUsageView.LedgerEntry> calls = dsl.select(
                        AI_TOKEN_USAGE_LEDGER.CALL_ID, AI_MODEL.MODEL_KEY,
                        AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND, AI_TOKEN_USAGE_LEDGER.AGENT_ID,
                        AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS,
                        AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS,
                        AI_TOKEN_USAGE_LEDGER.USAGE_COMPLETE, AI_TOKEN_USAGE_LEDGER.STATUS,
                        AI_TOKEN_USAGE_LEDGER.FAILURE_TYPE,
                        AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP,
                        AI_TOKEN_USAGE_LEDGER.SETTLED_TIMESTAMP)
                .from(AI_TOKEN_USAGE_LEDGER)
                .join(AI_MODEL).on(AI_MODEL.AI_MODEL_ID.eq(AI_TOKEN_USAGE_LEDGER.AI_MODEL_ID))
                .where(condition).orderBy(order)
                .limit((int) Math.min(offset, total), pageRequest.pageSize())
                .fetch(row -> new AiAdminUsageView.LedgerEntry(
                        row.get(AI_TOKEN_USAGE_LEDGER.CALL_ID), row.get(AI_MODEL.MODEL_KEY),
                        row.get(AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND),
                        row.get(AI_TOKEN_USAGE_LEDGER.AGENT_ID),
                        row.get(AI_TOKEN_USAGE_LEDGER.RESERVED_TOKENS).longValue(),
                        row.get(AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS).longValue(),
                        row.get(AI_TOKEN_USAGE_LEDGER.USAGE_COMPLETE) == 1,
                        row.get(AI_TOKEN_USAGE_LEDGER.STATUS),
                        row.get(AI_TOKEN_USAGE_LEDGER.FAILURE_TYPE),
                        utc(row.get(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP)),
                        utc(row.get(AI_TOKEN_USAGE_LEDGER.SETTLED_TIMESTAMP))));

        var usage = new AiAdminUsageView.PeriodUsage(
                totals != null ? totals.value1() : 0L,
                totals != null ? totals.value2() : 0L, total, start, end);
        var page = new PageResponse<>(calls, pageRequest.pageIndex(),
                pageRequest.pageSize(), total);
        return new PeriodPage(usage, page);
    }

    static List<SortField<?>> order(PageRequest pageRequest) {
        List<SortField<?>> order = new ArrayList<>();
        pageRequest.sorts().forEach(sort -> {
            Field<?> field = switch (sort.field()) {
                case "time" -> AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP;
                case "model" -> AI_MODEL.MODEL_KEY;
                case "kind" -> AI_TOKEN_USAGE_LEDGER.EXECUTION_KIND;
                case "status" -> AI_TOKEN_USAGE_LEDGER.STATUS;
                case "charged" -> AI_TOKEN_USAGE_LEDGER.CHARGED_TOKENS;
                default -> null;
            };
            if (field != null) {
                order.add(sort.direction() == SortDirection.DESC ? field.desc() : field.asc());
            }
        });
        if (order.isEmpty()) order.add(AI_TOKEN_USAGE_LEDGER.RESERVED_TIMESTAMP.desc());
        order.add(AI_TOKEN_USAGE_LEDGER.CALL_ID.desc());
        return order;
    }

    private static Instant utc(java.time.LocalDateTime value) {
        return value != null ? value.toInstant(ZoneOffset.UTC) : null;
    }

    public record PeriodPage(AiAdminUsageView.PeriodUsage usage,
                             PageResponse<AiAdminUsageView.LedgerEntry> calls) {
    }
}
