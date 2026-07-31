package org.oagi.score.gateway.http.configuration.handler;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationSecretUnavailableExceptionHandlerTest {

    @Test
    void reportsMissingSecretEncryptionConfigurationAsServiceUnavailable() {
        var response = new ScoreResponseEntityExceptionHandler()
                .handleApplicationSecretUnavailableException(
                        new ApplicationSecretUnavailableException(
                                "Application secret encryption key is not configured."), null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("not configured");
        assertThat(response.getHeaders().getFirst("X-Error-Message"))
                .contains("not configured");
    }
}
