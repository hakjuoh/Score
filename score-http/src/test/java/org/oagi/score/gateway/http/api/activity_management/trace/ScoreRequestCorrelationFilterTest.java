package org.oagi.score.gateway.http.api.activity_management.trace;

import io.opentelemetry.api.baggage.Baggage;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.service.OpenTelemetryScoreActivityContextProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_ID_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_ID_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TIMESTAMP_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TIMESTAMP_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TYPE_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TYPE_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.TRACE_ID_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.WEB_VERSION_HEADER;

class ScoreRequestCorrelationFilterTest {

    @Test
    void mapsUiHeadersToBaggageAndAResponseCorrelationTrace() throws Exception {
        Instant timestamp = Instant.parse("2026-08-06T12:00:00Z");
        ScoreRequestCorrelationFilter filter = new ScoreRequestCorrelationFilter(
                Clock.fixed(timestamp.plusSeconds(1), ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/releases/42/draft");
        request.addHeader(REQUEST_TYPE_HEADER, "RELEASE_DRAFT");
        request.addHeader(REQUEST_ID_HEADER, "ui-request-42");
        request.addHeader(REQUEST_TIMESTAMP_HEADER, timestamp.toString());
        request.addHeader(WEB_VERSION_HEADER, "3.6.0-dev");
        request.addHeader("Origin", "https://score.example.org");
        request.addHeader("Referer", "https://score.example.org/release/42?token=private#fragment");
        request.addHeader("Forwarded", "for=203.0.113.10;proto=https;host=score.example.org");
        request.addHeader("X-Forwarded-Host", "score.example.org");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Prefix", "/score");
        request.addHeader("X-Forwarded-Server", "edge-proxy");
        request.addHeader("Via", "1.1 edge-proxy");
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.setRemoteAddr("10.0.0.5");
        request.setRemotePort(51234);
        request.setServerPort(8080);
        request.setProtocol("HTTP/2");
        request.setQueryString("page=1&access_token=private");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Baggage> observedBaggage = new AtomicReference<>();
        AtomicReference<ScoreActivityContext> observedActivity = new AtomicReference<>();
        ScoreActivityTracing tracing = new ScoreActivityTracing();

        filter.doFilter(request, response, (req, res) -> {
            observedBaggage.set(Baggage.current());
            try (var activity = tracing.start(new ScoreActivityInvocation(
                    "release", "state-change", List.of()))) {
                observedActivity.set(new OpenTelemetryScoreActivityContextProvider().currentContext());
            }
        });

        assertThat(observedBaggage.get().getEntryValue(REQUEST_TYPE_BAGGAGE)).isEqualTo("RELEASE_DRAFT");
        assertThat(observedBaggage.get().getEntryValue(REQUEST_ID_BAGGAGE)).isEqualTo("ui-request-42");
        assertThat(observedBaggage.get().getEntryValue(REQUEST_TIMESTAMP_BAGGAGE))
                .isEqualTo(timestamp.toString());
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(observedActivity.get().traceId());
        assertThat(observedActivity.get().parentSpanId()).isNull();
        assertThat(response.getHeader(REQUEST_ID_HEADER)).isEqualTo("ui-request-42");
        assertThat(observedActivity.get().httpRequest()).satisfies(http -> {
            assertThat(http.method()).isEqualTo("POST");
            assertThat(http.scheme()).isEqualTo("https");
            assertThat(http.serverAddress()).isEqualTo("score.example.org");
            assertThat(http.serverPort()).isEqualTo(443);
            assertThat(http.path()).isEqualTo("/releases/42/draft");
            assertThat(http.query()).isEqualTo("page=1&access_token=REDACTED");
            assertThat(http.protocolVersion()).isEqualTo("2");
            assertThat(http.clientAddress()).isEqualTo("203.0.113.10");
            assertThat(http.networkPeerAddress()).isEqualTo("10.0.0.5");
            assertThat(http.uiOrigin()).isEqualTo("https://score.example.org");
            assertThat(http.uiPageUrl()).isEqualTo("https://score.example.org/release/42");
            assertThat(http.webVersion()).isEqualTo("3.6.0-dev");
            assertThat(http.clientAddressSource()).isEqualTo("forwarded");
            assertThat(http.proxyHeaders()).containsEntry(
                    "x-forwarded-server", List.of("edge-proxy"));
            assertThat(http.proxyHeaders()).containsEntry("x-forwarded-prefix", List.of("/score"));
            assertThat(http.proxyHeaders()).containsEntry("via", List.of("1.1 edge-proxy"));
        });
    }

    @Test
    void preservesAnInboundW3cTraceAsTheActivityParent() throws Exception {
        ScoreRequestCorrelationFilter filter = new ScoreRequestCorrelationFilter(Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/releases/42/draft");
        request.addHeader("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-00");
        request.addHeader("tracestate", "vendor=state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ScoreActivityContext> observed = new AtomicReference<>();
        ScoreActivityTracing tracing = new ScoreActivityTracing();

        filter.doFilter(request, response, (req, res) -> {
            try (var activity = tracing.start(new ScoreActivityInvocation(
                    "release", "state-change", List.of()))) {
                observed.set(new OpenTelemetryScoreActivityContextProvider().currentContext());
            }
        });

        assertThat(observed.get().traceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(observed.get().parentSpanId()).isEqualTo("0123456789abcdef");
        assertThat(observed.get().traceFlags()).isEqualTo("00");
        assertThat(observed.get().traceState()).isEqualTo("vendor=state");
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(observed.get().traceId());
    }

    @Test
    void replacesMalformedOptionalHeadersWithSafeServerValues() throws Exception {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        ScoreRequestCorrelationFilter filter = new ScoreRequestCorrelationFilter(
                Clock.fixed(now, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/releases/42/draft");
        request.addHeader(REQUEST_TYPE_HEADER, "bad header value");
        request.addHeader(REQUEST_ID_HEADER, "\nunsafe");
        request.addHeader(REQUEST_TIMESTAMP_HEADER, "not-an-instant");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(REQUEST_TYPE_HEADER)).isEqualTo("UI_HTTP_REQUEST");
        assertThat(response.getHeader(REQUEST_ID_HEADER)).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(REQUEST_TIMESTAMP_HEADER)).isEqualTo(now.toString());
    }

    @Test
    void usesValidatedW3cBaggageWhenCustomHeadersAreAbsent() throws Exception {
        Instant timestamp = Instant.parse("2026-08-06T12:00:00Z");
        ScoreRequestCorrelationFilter filter = new ScoreRequestCorrelationFilter(
                Clock.fixed(timestamp.plusSeconds(1), ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/releases/42/draft");
        request.addHeader("baggage", "score.request.type=CONNECT_CENTER_REQUEST,"
                + "score.request.id=connect-request-42,"
                + "score.request.timestamp=2026-08-06T12%3A00%3A00Z,"
                + "untrusted.key=discard-me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Baggage> observed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observed.set(Baggage.current()));

        assertThat(response.getHeader(REQUEST_TYPE_HEADER)).isEqualTo("CONNECT_CENTER_REQUEST");
        assertThat(response.getHeader(REQUEST_ID_HEADER)).isEqualTo("connect-request-42");
        assertThat(response.getHeader(REQUEST_TIMESTAMP_HEADER)).isEqualTo(timestamp.toString());
        assertThat(observed.get().asMap()).containsOnlyKeys(
                REQUEST_TYPE_BAGGAGE, REQUEST_ID_BAGGAGE, REQUEST_TIMESTAMP_BAGGAGE);
    }

    @Test
    void capturesMajorProxyAndCdnHeadersWithoutCapturingArbitraryOrSensitiveHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/core-components/acc");
        request.addHeader("CF-Connecting-IP", "203.0.113.20");
        request.addHeader("CF-Ray", "abc123-EWR");
        request.addHeader("X-Amzn-Trace-Id", "Root=1-67891233-abcdef012345678912345678");
        request.addHeader("X-Envoy-External-Address", "203.0.113.21");
        request.addHeader("X-Azure-ClientIP", "203.0.113.22");
        request.addHeader("X-Azure-Ref", "azure-reference");
        request.addHeader("X-Cloud-Trace-Context", "trace-id/1;o=1");
        request.addHeader("Fastly-Client-IP", "203.0.113.23");
        request.addHeader("X-Served-By", "cache-ewr-kewr1740021-EWR");
        request.addHeader("Akamai-GRN", "0.12345678.1234567890.abcdef");
        request.addHeader("Via", "1.1 edge-one");
        request.addHeader("Via", "1.1 edge-two");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "session=secret");
        request.addHeader("X-Unlisted-Proxy-Header", "must-not-be-captured");

        var captured = ScoreHttpRequestCapture.capture(request);

        assertThat(captured.proxyHeaders())
                .containsEntry("cf-connecting-ip", List.of("203.0.113.20"))
                .containsEntry("cf-ray", List.of("abc123-EWR"))
                .containsEntry("x-amzn-trace-id", List.of("Root=1-67891233-abcdef012345678912345678"))
                .containsEntry("x-envoy-external-address", List.of("203.0.113.21"))
                .containsEntry("x-azure-clientip", List.of("203.0.113.22"))
                .containsEntry("x-azure-ref", List.of("azure-reference"))
                .containsEntry("x-cloud-trace-context", List.of("trace-id/1;o=1"))
                .containsEntry("fastly-client-ip", List.of("203.0.113.23"))
                .containsEntry("x-served-by", List.of("cache-ewr-kewr1740021-EWR"))
                .containsEntry("akamai-grn", List.of("0.12345678.1234567890.abcdef"))
                .containsEntry("via", List.of("1.1 edge-one", "1.1 edge-two"))
                .doesNotContainKeys("authorization", "cookie", "x-unlisted-proxy-header");
        assertThat(captured.clientAddress()).isEqualTo("203.0.113.20");
        assertThat(captured.clientAddressSource()).isEqualTo("cf-connecting-ip");
    }

    @Test
    void resolvesCommonClientAddressHeadersAndPreservesTheForwardedChain() {
        Map<String, String> candidates = Map.of(
                "X-Forwarded-For", "203.0.113.30, 10.0.0.8",
                "True-Client-IP", "203.0.113.31",
                "X-Azure-ClientIP", "203.0.113.32",
                "X-Envoy-External-Address", "203.0.113.33",
                "Fastly-Client-IP", "203.0.113.34",
                "X-Real-IP", "203.0.113.35");

        candidates.forEach((header, value) -> {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api");
            request.addHeader(header, value);
            request.setRemoteAddr("10.0.0.5");

            var captured = ScoreHttpRequestCapture.capture(request);

            assertThat(captured.clientAddress()).isEqualTo(value.split(",", 2)[0]);
            assertThat(captured.clientAddressSource()).isEqualTo(header.toLowerCase());
            assertThat(captured.proxyHeaders()).containsEntry(
                    header.toLowerCase(), List.of(value));
        });
    }

    @Test
    void usesCommonSchemeAndOriginalHostFallbacksAndBoundsProxyMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api");
        request.addHeader("CF-Visitor", "{\"scheme\":\"https\"}");
        request.addHeader("X-Original-Host", "score.example.org");
        request.addHeader("X-Forwarded-Port", "8443");
        request.addHeader("X-Request-ID", "r".repeat(10_000));
        request.setScheme("http");
        request.setServerPort(8080);

        var captured = ScoreHttpRequestCapture.capture(request);

        assertThat(captured.scheme()).isEqualTo("https");
        assertThat(captured.serverAddress()).isEqualTo("score.example.org");
        assertThat(captured.serverPort()).isEqualTo(8443);
        assertThat(captured.proxyHeaders().get("x-request-id").getFirst()).hasSize(2_048);
        assertThat(captured.proxyHeaders().values().stream()
                .flatMap(List::stream).mapToInt(String::length).sum()).isLessThanOrEqualTo(8_192);
    }
}
