package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderConnectionTestResult;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.configuration.ai.AiProviderEndpointResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Performs a bounded, read-only provider API call without persisting draft settings. */
@Service
public class AiProviderConnectionTester {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HostResolver hostResolver;

    @Autowired
    public AiProviderConnectionTester(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), objectMapper, InetAddress::getAllByName);
    }

    AiProviderConnectionTester(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, InetAddress::getAllByName);
    }

    AiProviderConnectionTester(HttpClient httpClient, ObjectMapper objectMapper,
                               HostResolver hostResolver) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.hostResolver = hostResolver;
    }

    public AiProviderConnectionTestResult test(AiProviderUpdate provider, char[] apiKey,
                                               String providerModelName) {
        if (apiKey == null || apiKey.length == 0) {
            return failed("An API key is required to test this provider.", null);
        }
        if ("anthropic".equals(normalizedType(provider))
                && (providerModelName == null || providerModelName.isBlank())) {
            return failed("Configure a model for this provider before testing the connection.",
                    null);
        }
        try {
            HttpRequest request = request(provider, new String(apiKey), providerModelName);
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            return result(response.statusCode());
        } catch (java.net.http.HttpTimeoutException exception) {
            return failed("The provider connection timed out after 10 seconds.", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed("The provider connection test was interrupted.", null);
        } catch (IOException exception) {
            return failed("The provider endpoint could not be reached.", null);
        } catch (IllegalArgumentException exception) {
            return failed("The provider endpoint or API key is invalid.", null);
        }
    }

    private HttpRequest request(AiProviderUpdate provider, String apiKey,
                                String providerModelName) throws IOException {
        String type = normalizedType(provider);
        String endpoint = switch (type) {
            case "anthropic" -> AiProviderEndpointResolver.anthropicBaseUrl(
                    provider.baseUrl(), provider.messagesUrl()) + "/v1/messages/count_tokens";
            case "openai" -> AiProviderEndpointResolver.openAiResponsesBaseUrl(
                    provider.baseUrl(), StringUtils.hasText(provider.apiVersion())) + "/models";
            default -> throw new IllegalArgumentException("Unsupported provider type");
        };
        URI endpointUri = URI.create(endpoint);
        requirePublicHttpsEndpoint(endpointUri, type);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpointUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if ("anthropic".equals(type)) {
            request.header("x-api-key", apiKey)
                    .header("anthropic-version", textOrDefault(
                            provider.apiVersion(), DEFAULT_ANTHROPIC_VERSION))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                            Map.of("model", providerModelName,
                                    "messages", List.of(Map.of("role", "user",
                                            "content", "connection test"))))));
        } else if (StringUtils.hasText(provider.apiVersion())) {
            request.header("api-key", apiKey).GET();
        } else {
            request.header("Authorization", "Bearer " + apiKey).GET();
        }
        return request.build();
    }

    private AiProviderConnectionTestResult result(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return new AiProviderConnectionTestResult(true,
                    "Connection successful. The endpoint accepted the API key.", statusCode);
        }
        if (statusCode == 401 || statusCode == 403) {
            return failed("Authentication failed. Check the API key and provider type.", statusCode);
        }
        if (statusCode == 404) {
            return failed("The provider API endpoint was not found. Check the URL.", statusCode);
        }
        return failed("The provider returned HTTP " + statusCode + ".", statusCode);
    }

    private static AiProviderConnectionTestResult failed(String message, Integer statusCode) {
        return new AiProviderConnectionTestResult(false, message, statusCode);
    }

    private static String textOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value.strip() : fallback;
    }

    private static String normalizedType(AiProviderUpdate provider) {
        return provider.providerType().strip().toLowerCase();
    }

    private void requirePublicHttpsEndpoint(URI endpoint, String providerType) throws IOException {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Provider endpoint must be a public HTTPS URL");
        }
        String host = endpoint.getHost().toLowerCase();
        boolean trusted = switch (providerType) {
            case "anthropic" -> host.equals("api.anthropic.com")
                    || host.endsWith(".services.ai.azure.com");
            case "openai" -> host.equals("api.openai.com")
                    || host.endsWith(".openai.azure.com")
                    || host.endsWith(".services.ai.azure.com");
            default -> false;
        };
        if (!trusted) {
            throw new IllegalArgumentException("Provider endpoint host is not trusted");
        }
        InetAddress[] addresses = hostResolver.resolve(endpoint.getHost());
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Provider endpoint host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("Provider endpoint resolves to a private address");
            }
        }
    }

    private static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) return false;
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return !(first == 0 || first == 100 && second >= 64 && second <= 127);
        }
        return true;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws IOException;
    }
}
