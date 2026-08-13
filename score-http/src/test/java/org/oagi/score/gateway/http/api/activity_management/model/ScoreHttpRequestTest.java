package org.oagi.score.gateway.http.api.activity_management.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreHttpRequestTest {

    @Test
    void rejectsHeadersOutsideTheExplicitProxyAllowlist() {
        assertThatThrownBy(() -> request(Map.of(
                "authorization", List.of("Bearer secret"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported proxy header");
    }

    @Test
    void rejectsAnUnsupportedClientAddressSource() {
        assertThatThrownBy(() -> new ScoreHttpRequest(
                "GET", null, "https", "score.example.org", 443,
                "/api", null, "2", "203.0.113.10", "10.0.0.5", 51234,
                null, null, null, null, null, "untrusted-header", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported client address source");
    }

    private static ScoreHttpRequest request(Map<String, List<String>> proxyHeaders) {
        return new ScoreHttpRequest(
                "GET", null, "https", "score.example.org", 443,
                "/api", null, "2", "203.0.113.10", "10.0.0.5", 51234,
                null, null, null, null, null, "x-forwarded-for", proxyHeaders);
    }
}
