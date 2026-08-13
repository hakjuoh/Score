package org.oagi.score.gateway.http.api.activity_management.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sanitized HTTP and UI metadata captured at the SCORE ingress boundary.
 * Values in this record are observability data only and must never be used for authorization.
 */
public record ScoreHttpRequest(
        String method,
        String originalMethod,
        String scheme,
        String serverAddress,
        Integer serverPort,
        String path,
        String query,
        String protocolVersion,
        String clientAddress,
        String networkPeerAddress,
        Integer networkPeerPort,
        String userAgent,
        String uiOrigin,
        String uiPageUrl,
        String webVersion,
        String serverSoftware,
        String clientAddressSource,
        Map<String, List<String>> proxyHeaders) {

    public static final int MAX_PROXY_HEADER_VALUE_LENGTH = 2_048;
    public static final int MAX_PROXY_HEADER_VALUES = 8;
    public static final int MAX_PROXY_METADATA_LENGTH = 8_192;
    private static final List<String> PROXY_HEADER_ALLOWLIST = List.of(
            "forwarded", "via",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
            "x-forwarded-port", "x-forwarded-server", "x-forwarded-prefix", "x-forwarded-scheme",
            "x-forwarded-protocol", "x-scheme", "x-original-host", "x-real-ip",
            "x-request-id", "x-correlation-id", "front-end-https",
            "cf-connecting-ip", "true-client-ip", "cf-ray", "cf-visitor", "cdn-loop",
            "x-amzn-trace-id", "x-envoy-external-address",
            "x-azure-clientip", "x-azure-ref", "x-cloud-trace-context",
            "fastly-client-ip", "x-served-by", "x-cache", "akamai-grn");
    private static final Set<String> CLIENT_ADDRESS_SOURCES = Set.of(
            "forwarded", "x-forwarded-for", "cf-connecting-ip", "true-client-ip",
            "x-azure-clientip", "x-envoy-external-address", "fastly-client-ip",
            "x-real-ip", "network.peer.address");

    public ScoreHttpRequest {
        if (clientAddressSource != null && !CLIENT_ADDRESS_SOURCES.contains(clientAddressSource)) {
            throw new IllegalArgumentException("Unsupported client address source: " + clientAddressSource);
        }
        if (proxyHeaders == null || proxyHeaders.isEmpty()) {
            proxyHeaders = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            int totalLength = 0;
            for (Map.Entry<String, List<String>> header : proxyHeaders.entrySet()) {
                String name = header.getKey();
                List<String> values = List.copyOf(header.getValue());
                if (!PROXY_HEADER_ALLOWLIST.contains(name)) {
                    throw new IllegalArgumentException("Unsupported proxy header: " + name);
                }
                if (values.size() > MAX_PROXY_HEADER_VALUES) {
                    throw new IllegalArgumentException("Too many values for proxy header: " + name);
                }
                for (String value : values) {
                    if (value.length() > MAX_PROXY_HEADER_VALUE_LENGTH) {
                        throw new IllegalArgumentException("Proxy header value is too long: " + name);
                    }
                    totalLength += value.length();
                }
                copy.put(name, values);
            }
            if (totalLength > MAX_PROXY_METADATA_LENGTH) {
                throw new IllegalArgumentException("Proxy header metadata exceeds the 8 KiB limit");
            }
            proxyHeaders = Map.copyOf(copy);
        }
    }

    public static List<String> proxyHeaderAllowlist() {
        return PROXY_HEADER_ALLOWLIST;
    }
}
