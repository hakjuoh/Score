package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.util.StringUtils;

import java.net.URI;

/** Resolves provider endpoint conventions shared by runtime clients and diagnostics. */
public final class AiProviderEndpointResolver {

    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

    private AiProviderEndpointResolver() {
    }

    public static String anthropicBaseUrl(String configuredBaseUrl, String messagesUrl) {
        if (StringUtils.hasText(configuredBaseUrl)) {
            String baseUrl = trimTrailingSlashes(configuredBaseUrl);
            URI uri = URI.create(baseUrl);
            String path = uri.getPath();
            if (uri.getHost() != null && uri.getHost().endsWith(".services.ai.azure.com")
                    && (!StringUtils.hasText(path) || "/".equals(path))) {
                return baseUrl + "/anthropic";
            }
            return baseUrl;
        }
        if (!StringUtils.hasText(messagesUrl)) {
            return null;
        }
        URI uri = URI.create(messagesUrl);
        String path = uri.getPath();
        if (path == null || !path.endsWith("/v1/messages")) {
            throw new IllegalArgumentException("Anthropic messages URL must end in /v1/messages");
        }
        String basePath = path.substring(0, path.length() - "/v1/messages".length());
        return uri.getScheme() + "://" + uri.getAuthority() + basePath;
    }

    public static String openAiResponsesBaseUrl(String configuredBaseUrl, boolean azure) {
        String baseUrl = trimTrailingSlashes(configuredBaseUrl);
        if (!StringUtils.hasText(baseUrl)) return DEFAULT_OPENAI_BASE_URL;
        if (!azure || baseUrl.endsWith("/openai/v1")) return baseUrl;
        return baseUrl + "/openai/v1";
    }

    public static String trimTrailingSlashes(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("/+$", "") : null;
    }
}
