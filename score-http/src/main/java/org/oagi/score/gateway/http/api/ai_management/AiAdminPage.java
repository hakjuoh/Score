package org.oagi.score.gateway.http.api.ai_management;

import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.common.model.Sort;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.ArrayList;

/** Shared paging and text matching rules for the AI administration list screens. */
public final class AiAdminPage {

    private static final int MAX_PAGE_SIZE = 100;

    private AiAdminPage() {
    }

    public static <T> PageResponse<T> of(Stream<T> values, PageRequest request,
                                         Function<Sort, Comparator<T>> comparatorFor,
                                         Comparator<T> fallback) {
        validate(request);
        Comparator<T> comparator = request.sorts().stream()
                .map(sort -> directed(comparatorFor.apply(sort), sort))
                .filter(java.util.Objects::nonNull)
                .reduce(Comparator::thenComparing)
                .orElse(fallback);
        List<T> sorted = values.sorted(comparator).toList();
        int from = (int) Math.min((long) request.pageIndex() * request.pageSize(),
                sorted.size());
        int to = Math.min(from + request.pageSize(), sorted.size());
        return new PageResponse<>(sorted.subList(from, to), request.pageIndex(),
                request.pageSize(), sorted.size());
    }

    public static void validate(PageRequest request) {
        if (request == null || request.pageIndex() < 0 || request.pageSize() < 1
                || request.pageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page index must be non-negative and page size must be between 1 and 100.");
        }
    }

    public static long offset(PageRequest request) {
        validate(request);
        return (long) request.pageIndex() * request.pageSize();
    }

    public static boolean contains(String value, String query) {
        return !hasText(query) || value != null
                && value.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Builds the shared updater selector used by score-tab-filter-select. Plain values are
     * inclusions; values prefixed with {@code !} are exclusions. Excluding an updater retains
     * rows with no updater because those rows were not updated by the excluded account.
     */
    public static Condition loginIdSelection(Field<String> loginId,
                                             List<String> selections) {
        if (selections == null || selections.isEmpty()) return DSL.trueCondition();
        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        selections.stream().filter(AiAdminPage::hasText).map(String::strip).forEach(value -> {
            if (value.startsWith("!") && value.length() > 1) {
                excluded.add(value.substring(1));
            } else {
                included.add(value);
            }
        });
        Condition condition = DSL.trueCondition();
        if (!included.isEmpty()) condition = condition.and(loginId.in(included));
        if (!excluded.isEmpty()) {
            condition = condition.and(loginId.notIn(excluded).or(loginId.isNull()));
        }
        return condition;
    }

    private static <T> Comparator<T> directed(Comparator<T> comparator, Sort sort) {
        if (comparator == null) return null;
        return sort.direction() == SortDirection.DESC ? comparator.reversed() : comparator;
    }
}
