package org.oagi.score.gateway.http.api.activity_management.trace;

import jakarta.servlet.http.HttpServletRequest;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreHttpRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.WEB_VERSION_HEADER;

/** Captures bounded, sanitized request metadata without treating forwarded headers as trusted input. */
final class ScoreHttpRequestCapture {

    private static final int MAX_HEADER_LENGTH = 2_048;
    private static final int MAX_USER_AGENT_LENGTH = 1_024;
    private static final Set<String> KNOWN_METHODS = Set.of(
            "CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE");
    private static final Pattern WEB_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final Pattern CF_VISITOR_SCHEME = Pattern.compile(
            "\\\"scheme\\\"\\s*:\\s*\\\"(https?)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_QUERY_KEY = Pattern.compile(
            "(?i)(?:sig|.*(?:authorization|credential|password|secret|signature|token|api[-_]?key|access[-_]?key).*)");

    private ScoreHttpRequestCapture() {
    }

    static ScoreHttpRequest capture(HttpServletRequest request) {
        String originalMethod = bounded(request.getMethod(), 32);
        String method = originalMethod == null
                ? "_OTHER"
                : originalMethod.toUpperCase(Locale.ROOT);
        if (!KNOWN_METHODS.contains(method)) {
            method = "_OTHER";
        }

        Map<String, List<String>> proxyHeaders = proxyHeaders(request);
        String forwarded = firstProxyHeader(proxyHeaders, "forwarded");
        String scheme = firstNonBlank(
                unquote(forwardedParameter(forwarded, "proto")),
                firstProxyHeaderValue(proxyHeaders, "x-forwarded-proto"),
                firstProxyHeaderValue(proxyHeaders, "x-forwarded-scheme"),
                firstProxyHeaderValue(proxyHeaders, "x-forwarded-protocol"),
                firstProxyHeaderValue(proxyHeaders, "x-scheme"),
                cloudflareScheme(firstProxyHeader(proxyHeaders, "cf-visitor")),
                frontEndHttps(firstProxyHeader(proxyHeaders, "front-end-https")),
                request.getScheme());
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            scheme = request.getScheme();
        }
        String forwardedHost = firstNonBlank(
                unquote(forwardedParameter(forwarded, "host")),
                firstProxyHeaderValue(proxyHeaders, "x-forwarded-host"),
                firstProxyHeaderValue(proxyHeaders, "x-original-host"));
        String host = firstNonBlank(forwardedHost, request.getHeader("Host"), request.getServerName());
        int fallbackPort = forwardedHost == null
                ? request.getServerPort()
                : defaultPort(scheme);
        HostAndPort server = hostAndPort(host, fallbackPort);
        Integer forwardedPort = integer(firstProxyHeaderValue(proxyHeaders, "x-forwarded-port"));
        if (forwardedPort != null) {
            server = new HostAndPort(server.host(), forwardedPort);
        }

        ClientAddress client = clientAddress(forwarded, proxyHeaders, request);
        return new ScoreHttpRequest(
                method,
                "_OTHER".equals(method) ? originalMethod : null,
                lowerCase(scheme),
                server.host(),
                server.port(),
                bounded(request.getRequestURI(), MAX_HEADER_LENGTH),
                sanitizedQuery(request.getQueryString()),
                protocolVersion(request.getProtocol()),
                client.value(),
                bounded(request.getRemoteAddr(), 255),
                positivePort(request.getRemotePort()),
                bounded(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH),
                safeUri(request.getHeader("Origin"), false),
                safeUri(request.getHeader("Referer"), true),
                webVersion(request.getHeader(WEB_VERSION_HEADER)),
                request.getServletContext() == null
                        ? null
                        : bounded(request.getServletContext().getServerInfo(), 255),
                client.source(),
                proxyHeaders);
    }

    private static ClientAddress clientAddress(
            String forwarded,
            Map<String, List<String>> proxyHeaders,
            HttpServletRequest request) {
        LinkedHashMap<String, String> candidates = new LinkedHashMap<>();
        candidates.put("forwarded", forwardedParameter(forwarded, "for"));
        candidates.put("x-forwarded-for", firstProxyHeaderValue(proxyHeaders, "x-forwarded-for"));
        candidates.put("cf-connecting-ip", firstProxyHeaderValue(proxyHeaders, "cf-connecting-ip"));
        candidates.put("true-client-ip", firstProxyHeaderValue(proxyHeaders, "true-client-ip"));
        candidates.put("x-azure-clientip", firstProxyHeaderValue(proxyHeaders, "x-azure-clientip"));
        candidates.put("x-envoy-external-address",
                firstProxyHeaderValue(proxyHeaders, "x-envoy-external-address"));
        candidates.put("fastly-client-ip", firstProxyHeaderValue(proxyHeaders, "fastly-client-ip"));
        candidates.put("x-real-ip", firstProxyHeaderValue(proxyHeaders, "x-real-ip"));
        candidates.put("network.peer.address", request.getRemoteAddr());
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            String address = normalizedAddress(candidate.getValue());
            if (address != null) {
                return new ClientAddress(address, candidate.getKey());
            }
        }
        return new ClientAddress(null, null);
    }

    private static String normalizedAddress(String candidate) {
        candidate = firstHeaderValue(candidate);
        if (candidate == null) {
            return null;
        }
        candidate = unquote(candidate.strip());
        if ("unknown".equalsIgnoreCase(candidate) || candidate.startsWith("_")) {
            return null;
        }
        if (candidate.startsWith("[")) {
            int closingBracket = candidate.indexOf(']');
            return closingBracket > 0 ? bounded(candidate.substring(1, closingBracket), 255) : null;
        }
        int colon = candidate.indexOf(':');
        return bounded(colon > 0 && candidate.indexOf(':', colon + 1) < 0
                ? candidate.substring(0, colon) : candidate, 255);
    }

    private static Map<String, List<String>> proxyHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int remaining = ScoreHttpRequest.MAX_PROXY_METADATA_LENGTH;
        for (String headerName : ScoreHttpRequest.proxyHeaderAllowlist()) {
            List<String> values = new ArrayList<>();
            for (String value : Collections.list(request.getHeaders(headerName))) {
                if (values.size() == ScoreHttpRequest.MAX_PROXY_HEADER_VALUES || remaining == 0) {
                    break;
                }
                String captured = bounded(value, Math.min(
                        ScoreHttpRequest.MAX_PROXY_HEADER_VALUE_LENGTH, remaining));
                if (captured != null) {
                    values.add(captured);
                    remaining -= captured.length();
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            headers.put(headerName, List.copyOf(values));
            if (remaining == 0) {
                break;
            }
        }
        return Map.copyOf(headers);
    }

    private static String firstProxyHeader(
            Map<String, List<String>> headers,
            String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String firstProxyHeaderValue(
            Map<String, List<String>> headers,
            String name) {
        return firstHeaderValue(firstProxyHeader(headers, name));
    }

    private static String cloudflareScheme(String cfVisitor) {
        if (cfVisitor == null) {
            return null;
        }
        var matcher = CF_VISITOR_SCHEME.matcher(cfVisitor);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String frontEndHttps(String value) {
        return value != null && Set.of("on", "1", "true").contains(value.toLowerCase(Locale.ROOT))
                ? "https" : null;
    }

    private static String forwardedParameter(String forwarded, String parameter) {
        if (forwarded == null) {
            return null;
        }
        String firstElement = forwarded.split(",", 2)[0];
        for (String part : firstElement.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && parameter.equalsIgnoreCase(pair[0].strip())) {
                return pair[1].strip();
            }
        }
        return null;
    }

    private static HostAndPort hostAndPort(String value, int fallbackPort) {
        String host = bounded(firstHeaderValue(value), 255);
        Integer port = positivePort(fallbackPort);
        if (host == null) {
            return new HostAndPort(null, port);
        }
        try {
            URI authority = new URI("http://" + host);
            if (authority.getHost() != null) {
                return new HostAndPort(authority.getHost(),
                        authority.getPort() >= 0 ? authority.getPort() : port);
            }
        } catch (URISyntaxException ignored) {
            // Keep the bounded host value when the proxy supplied a non-RFC authority.
        }
        return new HostAndPort(host, port);
    }

    private static String safeUri(String value, boolean removeQuery) {
        value = bounded(value, MAX_HEADER_LENGTH);
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            return removeQuery
                    ? new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                            uri.getPath(), null, null).toString()
                    : new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                            null, null, null).toString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static String sanitizedQuery(String query) {
        query = bounded(query, MAX_HEADER_LENGTH);
        if (query == null) {
            return null;
        }
        return Arrays.stream(query.split("&", -1))
                .map(ScoreHttpRequestCapture::sanitizeQueryParameter)
                .collect(Collectors.joining("&"));
    }

    private static String sanitizeQueryParameter(String parameter) {
        String[] pair = parameter.split("=", 2);
        String key = decode(pair[0]);
        if (!SENSITIVE_QUERY_KEY.matcher(key).matches() || pair.length == 1) {
            return parameter;
        }
        return pair[0] + '=' + URLEncoder.encode("REDACTED", StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static String webVersion(String value) {
        value = bounded(value, 64);
        return value != null && WEB_VERSION.matcher(value).matches() ? value : null;
    }

    private static String protocolVersion(String protocol) {
        protocol = bounded(protocol, 32);
        if (protocol == null) {
            return null;
        }
        int separator = protocol.indexOf('/');
        return separator >= 0 && separator + 1 < protocol.length()
                ? protocol.substring(separator + 1)
                : protocol;
    }

    private static String firstHeaderValue(String value) {
        return value == null ? null : value.split(",", 2)[0].strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }

    private static String lowerCase(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static Integer integer(String value) {
        try {
            return value == null ? null : positivePort(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer positivePort(int value) {
        return value > 0 && value <= 65_535 ? value : null;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private record HostAndPort(String host, Integer port) {
    }

    private record ClientAddress(String value, String source) {
    }
}
