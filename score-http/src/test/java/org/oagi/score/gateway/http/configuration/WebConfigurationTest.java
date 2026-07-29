package org.oagi.score.gateway.http.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebConfigurationTest {

    @Test
    void servletAsyncTimeoutDoesNotOverrideRequestInactivityLease() {
        AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);

        new WebConfiguration(mock(ObjectMapper.class)).configureAsyncSupport(configurer);

        verify(configurer).setDefaultTimeout(-1L);
    }

}
