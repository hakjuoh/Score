package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Declarative, frontend-owned description of connectCenter UI routes.
 *
 * <p>The backend validates and canonicalizes this data before placing it in the
 * stable model prefix, but deliberately does not own any concrete route. A UI
 * deployment can therefore add or change paths without a matching backend
 * release as long as it continues to use this versioned schema.</p>
 */
public record AiUiRouteManifest(int schemaVersion, List<Route> routes) {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_ROUTES = 256;
    private static final int MAX_COLLECTION_ENTRIES = 128;
    private static final int MAX_PROMPT_CHARS = 100_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_./{}:-]{0,511}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z][A-Za-z0-9_-]{0,63}}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.,:!~+/@-]{0,255}");

    public AiUiRouteManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported UI route manifest schema version: " + schemaVersion);
        }
        routes = routes != null ? List.copyOf(routes) : List.of();
        if (routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("UI route manifest exceeds " + MAX_ROUTES + " routes.");
        }
        Set<String> resources = new LinkedHashSet<>();
        for (Route route : routes) {
            if (route == null || !resources.add(route.resource())) {
                throw new IllegalArgumentException("UI route manifest contains a null or duplicate resource.");
            }
        }
        routes = routes.stream().sorted(Comparator.comparing(Route::resource)).toList();
    }

    /** Returns a deterministic, instruction-free representation suitable for a cacheable prompt block. */
    public String promptText() {
        StringBuilder result = new StringBuilder("schemaVersion=").append(schemaVersion);
        for (Route route : routes) {
            result.append("\n- resource=").append(route.resource())
                    .append("; list=").append(route.listPath())
                    .append("; details=").append(formatMap(route.detailPatterns()))
                    .append("; idFields=").append(String.join("|", route.idFields()))
                    .append("; linkableFields=").append(String.join("|", route.linkableFields()));
            if (route.listQuery() != null) {
                ListQuery query = route.listQuery();
                result.append("; listQuery=codec=").append(query.codec())
                        .append("; defaultParams=").append(formatMap(query.defaultParams()))
                        .append("; allowedPlainParams=").append(String.join("|", query.allowedPlainParams()))
                        .append("; toolParamAliases=").append(formatMap(query.toolParamAliases()))
                        .append("; dateRangeParamAliases=").append(formatMap(query.dateRangeParamAliases()));
            }
        }
        if (result.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException(
                    "UI route manifest exceeds " + MAX_PROMPT_CHARS + " prompt characters.");
        }
        return result.toString();
    }

    /** Stable provider-routing hint; exact prompt content remains the cache correctness boundary. */
    public String promptCacheKey() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(promptText().getBytes(StandardCharsets.UTF_8));
            return "connectcenter-ui-routes-v" + schemaVersion + "-"
                    + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public record Route(String resource, String listPath, Map<String, String> detailPatterns,
                        List<String> idFields, List<String> linkableFields, ListQuery listQuery) {

        public Route {
            resource = requireName(resource, "resource");
            listPath = requirePath(listPath, "listPath");
            detailPatterns = normalizedMap(detailPatterns, true, true, "detailPatterns");
            idFields = normalizedNames(idFields, "idFields");
            linkableFields = normalizedNames(linkableFields, "linkableFields");
        }
    }

    public record ListQuery(String codec, Map<String, String> defaultParams,
                            List<String> allowedPlainParams, Map<String, String> toolParamAliases,
                            Map<String, String> dateRangeParamAliases) {

        private static final Set<String> CODECS = Set.of("plain", "base64-utf8-form");

        public ListQuery {
            if (!CODECS.contains(codec)) {
                throw new IllegalArgumentException("Unsupported UI route query codec: " + codec);
            }
            defaultParams = normalizedMap(defaultParams, false, false, "defaultParams");
            allowedPlainParams = normalizedNames(allowedPlainParams, "allowedPlainParams");
            toolParamAliases = normalizedMap(toolParamAliases, false, false, "toolParamAliases");
            dateRangeParamAliases = normalizedMap(
                    dateRangeParamAliases, false, false, "dateRangeParamAliases");
        }
    }

    private static List<String> normalizedNames(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException("UI route manifest " + field + " is too large.");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> normalized.add(requireName(value, field)));
        return List.copyOf(normalized);
    }

    private static Map<String, String> normalizedMap(Map<String, String> values,
                                                      boolean pathValues,
                                                      boolean allowDefaultKey,
                                                      String field) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException("UI route manifest " + field + " is too large.");
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String key = allowDefaultKey && "default".equals(entry.getKey())
                    ? "default" : requireName(entry.getKey(), field + " key");
            String value = pathValues
                    ? requirePath(entry.getValue(), field + " value")
                    : requireValue(entry.getValue(), field + " value");
            normalized.put(key, value);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static String requireName(String value, String field) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid UI route manifest " + field + ".");
        }
        return value;
    }

    private static String requirePath(String value, String field) {
        if (value == null || !PATH.matcher(value).matches() || value.startsWith("//")
                || value.contains("/../") || value.endsWith("/..")
                || !PLACEHOLDER.matcher(value).replaceAll("").chars()
                        .noneMatch(character -> character == '{' || character == '}')) {
            throw new IllegalArgumentException("Invalid UI route manifest " + field + ".");
        }
        return value;
    }

    private static String requireValue(String value, String field) {
        if (value == null || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid UI route manifest " + field + ".");
        }
        return value;
    }

    private static String formatMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "|" + right)
                .orElse("none");
    }
}
