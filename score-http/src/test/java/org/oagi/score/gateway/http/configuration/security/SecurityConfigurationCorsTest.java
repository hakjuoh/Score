package org.oagi.score.gateway.http.configuration.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationCorsTest {

    @Test
    void exposesCorrelationAndExistingErrorResponseHeaders() {
        var source = new SecurityConfiguration().corsConfigurationSource();
        var configuration = source.getCorsConfiguration(new MockHttpServletRequest("POST", "/api/releases/42/draft"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getExposedHeaders()).contains(
                "X-Score-Request-Type",
                "X-Score-Request-Id",
                "X-Score-Request-Timestamp",
                "X-Score-Trace-Id",
                "X-Error-Message",
                "X-Error-Message-Id",
                "X-Error-Code",
                "Retry-After");
    }
}
