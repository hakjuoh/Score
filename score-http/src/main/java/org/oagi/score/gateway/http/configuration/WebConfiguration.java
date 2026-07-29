package org.oagi.score.gateway.http.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebMvc
public class WebConfiguration implements WebMvcConfigurer {

    private static final long NO_ASYNC_TIMEOUT_MILLIS = -1L;

    private final ObjectMapper objectMapper;

    @Autowired
    public WebConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Compatibility bridge; servlet wall-clock timeouts are intentionally disabled. */
    @Deprecated
    public WebConfiguration(ObjectMapper objectMapper, Duration ignoredAsyncRequestTimeout) {
        this(objectMapper);
    }

    // @Primary so any unqualified RestTemplate injection resolves here; the GitHub integration uses a
    // dedicated, timeout-configured RestTemplate ("gitHubRestTemplate").
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // Spring Boot 4 / Spring Framework 7 select the Jackson 3 (tools.jackson) JSON converter whenever it is
    // on the classpath, and that converter ignores the Jackson 2 (com.fasterxml.jackson) @JsonSerialize
    // annotations this codebase still relies on — e.g. NodeSerializer on the CC/BIE graph Node, which
    // flattens Node.getProperties() onto the node object (the frontend CcGraphNode reads den/objectClassTerm/
    // componentType/... as top-level fields). During the Jackson 2 bridge period (#1750,
    // spring.jackson.use-jackson2-defaults=true), the spring-boot-jackson2 stop-gap only governs the
    // auto-configured Jackson 2 ObjectMapper bean, NOT the MVC message converter — and @EnableWebMvc here
    // additionally makes Spring Boot's converter customization back off. Force the REST layer to serialize
    // with the Boot-configured Jackson 2 ObjectMapper so the wire contract stays identical to Spring Boot
    // 3.5. Remove once the source is fully migrated to Jackson 3 (tools.jackson).
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof JacksonJsonHttpMessageConverter) {
                converters.set(i, new MappingJackson2HttpMessageConverter(objectMapper));
                return;
            }
        }
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Async AI requests own their lifecycle through AiRequestRegistry's rolling inactivity lease.
        // A servlet-level wall-clock timeout would terminate an actively progressing response without
        // giving that registry a chance to distinguish useful work from inactivity.
        configurer.setDefaultTimeout(NO_ASYNC_TIMEOUT_MILLIS);
    }

}
