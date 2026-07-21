package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request-scoped keyword index that ranks connectCenter entity names ahead of generic
 * operation words.
 *
 * <p>The framework regex index treats every word in a natural-language query as an OR
 * clause. Long workflow queries therefore let common verbs such as {@code get} and
 * {@code create} crowd the requested entity tools out of the result limit. This index
 * splits snake-case tool names, normalizes plural entity terms, and uses operation
 * synonyms only as a secondary signal.</p>
 */
public final class ScoreToolIndex implements ToolIndex {

    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int MAX_RESULTS = 10;
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "can", "need", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "before", "after",
            "and", "or", "but", "if", "this", "that", "these", "those", "what", "which",
            "who", "i", "me", "my", "we", "our", "you", "your", "it", "its", "they",
            "them", "their", "all", "any", "each", "every", "some", "no", "not", "only",
            "just", "also", "then", "current", "available", "specific", "detail", "new",
            "existing", "final", "back", "tool", "capability", "connectcenter", "id");
    private static final Map<String, String> ACTIONS = actionAliases();
    private static final Set<String> NAME_FILLERS = Set.of(
            "by", "id", "ids", "with", "from", "to", "for", "all");

    private final ConcurrentHashMap<String, List<IndexedTool>> sessions =
            new ConcurrentHashMap<>();

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        sessions.compute(sessionId, (ignored, current) -> {
            Map<String, ToolReference> references = new LinkedHashMap<>();
            if (current != null) {
                current.forEach(tool -> references.put(tool.reference().toolName(), tool.reference()));
            }
            references.put(toolReference.toolName(), toolReference);
            return indexed(references.values());
        });
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        Map<String, ToolReference> references = new LinkedHashMap<>();
        toolReferences.forEach(reference -> references.put(reference.toolName(), reference));
        sessions.put(sessionId, indexed(references.values()));
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest request) {
        long started = System.currentTimeMillis();
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return response(request, List.of(), started);
        }
        List<IndexedTool> tools = sessions.get(request.sessionId());
        if (tools == null || tools.isEmpty()) {
            return response(request, List.of(), started);
        }

        List<ToolReference> exactMatches = exactMatches(tools, request.query(), request.maxResults());
        if (!exactMatches.isEmpty()) {
            return response(request, exactMatches, started);
        }

        Query query = query(request.query(), request.categoryFilter());
        int limit = Math.min(MAX_RESULTS, Math.max(1,
                request.maxResults() != null ? request.maxResults() : DEFAULT_MAX_RESULTS));
        List<ToolReference> matches = tools.stream()
                .map(tool -> new Match(tool, score(query, tool)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingDouble(Match::score).reversed()
                        .thenComparing(match -> match.tool().reference().toolName()))
                .limit(limit)
                .map(match -> ToolReference.builder()
                        .toolName(match.tool().reference().toolName())
                        .summary(match.tool().reference().summary())
                        .relevanceScore(match.score())
                        .build())
                .toList();
        return response(request, matches, started);
    }

    /**
     * Lets the workflow model select names from the compact deferred-tool catalog without
     * running user-generated text through a regular-expression engine. This mirrors the
     * {@code select:Read,Edit} path used by Claude Code and remains case-insensitive for MCP
     * implementations that normalize tool names differently.
     */
    private List<ToolReference> exactMatches(List<IndexedTool> tools, String text,
                                             Integer requestedLimit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String query = text.strip();
        boolean explicitSelection = query.regionMatches(true, 0, "select:", 0, 7);
        String names = explicitSelection ? query.substring(7) : query;
        if (!explicitSelection && names.indexOf(',') >= 0) {
            return List.of();
        }

        Map<String, IndexedTool> byName = new LinkedHashMap<>();
        tools.forEach(tool -> byName.putIfAbsent(
                tool.reference().toolName().toLowerCase(Locale.ROOT), tool));
        int limit = Math.min(MAX_RESULTS, Math.max(1,
                requestedLimit != null ? requestedLimit : DEFAULT_MAX_RESULTS));
        List<ToolReference> matches = new ArrayList<>();
        for (String requestedName : names.split(",")) {
            IndexedTool tool = byName.get(requestedName.strip().toLowerCase(Locale.ROOT));
            if (tool != null && matches.stream().noneMatch(reference ->
                    reference.toolName().equals(tool.reference().toolName()))) {
                matches.add(ToolReference.builder()
                        .toolName(tool.reference().toolName())
                        .summary(tool.reference().summary())
                        .relevanceScore(100.0)
                        .build());
            }
            if (matches.size() >= limit) {
                break;
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public void clearIndex(String sessionId) {
        sessions.remove(sessionId);
    }

    private ToolSearchResponse response(ToolSearchRequest request,
                                        List<ToolReference> matches, long started) {
        return ToolSearchResponse.builder()
                .toolReferences(matches)
                .totalMatches(matches.size())
                .searchMetadata(ToolSearchResponse.SearchMetadata.builder()
                        .searchType(getClass().getSimpleName())
                        .query(request.query() != null ? request.query() : "")
                        .searchTimeMs(System.currentTimeMillis() - started)
                        .build())
                .build();
    }

    private List<IndexedTool> indexed(Iterable<ToolReference> references) {
        List<IndexedTool> tools = new ArrayList<>();
        for (ToolReference reference : references) {
            List<String> nameTerms = terms(reference.toolName());
            String action = nameTerms.isEmpty() ? null : ACTIONS.get(nameTerms.getFirst());
            List<String> entityTerms = nameTerms.stream()
                    .filter(term -> !NAME_FILLERS.contains(term))
                    .filter(term -> !ACTIONS.containsKey(term))
                    .toList();
            tools.add(new IndexedTool(reference, nameTerms, entityTerms,
                    semanticTerms(reference.summary()), action));
        }
        return List.copyOf(tools);
    }

    private Query query(String text, String category) {
        List<String> orderedTerms = new ArrayList<>(terms(text));
        if (category != null && !category.isBlank()) {
            orderedTerms.addAll(terms(category));
        }
        Set<String> actions = new LinkedHashSet<>();
        orderedTerms.stream().map(ACTIONS::get).filter(java.util.Objects::nonNull)
                .forEach(actions::add);
        Set<String> semantic = new LinkedHashSet<>(orderedTerms);
        semantic.removeAll(STOP_WORDS);
        semantic.removeIf(ACTIONS::containsKey);
        return new Query(List.copyOf(orderedTerms), Set.copyOf(semantic), Set.copyOf(actions));
    }

    private double score(Query query, IndexedTool tool) {
        if (query.orderedTerms().isEmpty()) {
            return 1.0;
        }

        long entityMatches = tool.entityTerms().stream()
                .filter(query.semanticTerms()::contains)
                .count();
        long descriptionMatches = tool.descriptionTerms().stream()
                .filter(query.semanticTerms()::contains)
                .distinct()
                .count();
        if (entityMatches == 0 && descriptionMatches == 0) {
            return 0.0;
        }

        double score = entityMatches * 12.0 + descriptionMatches * 1.5;
        if (!tool.entityTerms().isEmpty()) {
            double entityCoverage = (double) entityMatches / tool.entityTerms().size();
            score += entityCoverage * entityCoverage * 24.0;
            if (entityMatches == tool.entityTerms().size()) {
                score += 6.0 + tool.entityTerms().size() * 2.0;
            }
            if (containsSequence(query.orderedTerms(), tool.entityTerms())) {
                score += 20.0;
            }
        }

        if (tool.action() != null && !query.actions().isEmpty()) {
            score += query.actions().contains(tool.action()) ? 10.0 : -8.0;
        }
        if (containsSequence(query.orderedTerms(), tool.nameTerms())) {
            score += 30.0;
        }
        return score;
    }

    private boolean containsSequence(List<String> source, List<String> candidate) {
        if (candidate.isEmpty() || candidate.size() > source.size()) {
            return false;
        }
        for (int start = 0; start <= source.size() - candidate.size(); start++) {
            if (source.subList(start, start + candidate.size()).equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> semanticTerms(String text) {
        Set<String> result = new LinkedHashSet<>(terms(text));
        result.removeAll(STOP_WORDS);
        result.removeIf(ACTIONS::containsKey);
        return Set.copyOf(result);
    }

    private List<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String separated = CAMEL_BOUNDARY.matcher(text).replaceAll(" ")
                .replace('_', ' ').replace('-', ' ');
        Matcher matcher = WORD.matcher(separated.toLowerCase(Locale.ROOT));
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(singular(matcher.group()));
        }
        return List.copyOf(result);
    }

    private String singular(String term) {
        if (term.length() > 4 && term.endsWith("ies") && !term.endsWith("bies")) {
            return term.substring(0, term.length() - 3) + "y";
        }
        if (term.length() > 3 && term.endsWith("s")
                && !term.endsWith("ss") && !term.endsWith("us") && !term.endsWith("is")) {
            return term.substring(0, term.length() - 1);
        }
        return term;
    }

    private static Map<String, String> actionAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases(aliases, "get", "get", "list", "search", "find", "retrieve", "read",
                "show", "inspect", "lookup", "check", "verify", "view");
        aliases(aliases, "create", "create", "add", "assign", "associate", "link", "attach",
                "include", "register");
        aliases(aliases, "update", "update", "updated", "change", "edit", "modify", "replace",
                "rename", "set", "move");
        aliases(aliases, "delete", "delete", "remove", "unlink", "unassign", "detach");
        aliases(aliases, "copy", "copy", "clone", "duplicate", "reuse");
        aliases(aliases, "import", "import", "upload");
        aliases(aliases, "export", "export", "download", "generate");
        return Map.copyOf(aliases);
    }

    private static void aliases(Map<String, String> aliases, String action, String... words) {
        for (String word : words) {
            aliases.put(word, action);
        }
    }

    private record IndexedTool(ToolReference reference, List<String> nameTerms,
                               List<String> entityTerms, Set<String> descriptionTerms,
                               String action) {
    }

    private record Query(List<String> orderedTerms, Set<String> semanticTerms,
                         Set<String> actions) {
    }

    private record Match(IndexedTool tool, double score) {
    }
}
