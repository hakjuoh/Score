package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiProviderConnectionTesterTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testsAnthropicThroughTheNonBillableTokenCountEndpointAndConfiguredVersion()
            throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("anthropic",
                "https://example.services.ai.azure.com", "2025-01-01"),
                "test-key".toCharArray(), "claude-sonnet-5");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(result.successful()).isTrue();
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://example.services.ai.azure.com/anthropic/v1/messages/count_tokens");
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().bodyPublisher()).isPresent();
        assertThat(request.getValue().headers().firstValue("x-api-key"))
                .contains("test-key");
        assertThat(request.getValue().headers().firstValue("anthropic-version"))
                .contains("2025-01-01");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void preservesTheLegacyAzureOpenAiAdapterAndClassifiesAuthenticationFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("azure-openai",
                "https://unit.openai.azure.com", null), "test-key".toCharArray(), null);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(result.successful()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.message()).contains("Authentication failed");
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://unit.openai.azure.com/openai/v1/models");
        assertThat(request.getValue().headers().firstValue("api-key")).contains("test-key");
        assertThat(request.getValue().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testsStandardOpenAiThroughModelsWithBearerAuthentication() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("openai", "https://api.openai.com/v1/", null),
                "test-key".toCharArray(), null);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(result.successful()).isTrue();
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().headers().firstValue("Authorization"))
                .contains("Bearer test-key");
        assertThat(request.getValue().headers().firstValue("api-key")).isEmpty();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reportsAReachabilityFailureWithoutReturningLowLevelExceptionDetails() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("secret network detail"));
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("openai", "https://api.openai.com/v1", null),
                "test-key".toCharArray(), null);

        assertThat(result.successful()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.message()).isEqualTo("The provider endpoint could not be reached.");
        assertThat(result.message()).doesNotContain("secret network detail");
    }

    @Test
    void rejectsATestWithoutAnApiKeyBeforeMakingARequest() {
        HttpClient client = mock(HttpClient.class);
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("openai", "https://api.openai.com/v1", null), null, null);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("API key is required");
        verifyNoInteractions(client);
    }

    @Test
    void explainsThatAnthropicNeedsAConfiguredModelForItsNonBillableTest() {
        HttpClient client = mock(HttpClient.class);
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("anthropic", "https://api.anthropic.com", null),
                "test-key".toCharArray(), null);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("Configure a model");
        verifyNoInteractions(client);
    }

    @Test
    void rejectsAnEndpointThatResolvesToAPrivateAddressBeforeSendingTheKey() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AiProviderConnectionTester tester = new AiProviderConnectionTester(client,
                new ObjectMapper(), host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        var result = tester.test(provider("openai", "https://private.services.ai.azure.com/v1", null),
                "test-key".toCharArray(), null);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("endpoint or API key is invalid");
        verifyNoInteractions(client);
    }

    @Test
    void rejectsPlainHttpBeforeSendingTheKey() {
        HttpClient client = mock(HttpClient.class);
        AiProviderConnectionTester tester = tester(client);

        var result = tester.test(provider("openai", "http://provider.example/v1", null),
                "test-key".toCharArray(), null);

        assertThat(result.successful()).isFalse();
        verifyNoInteractions(client);
    }

    private AiProviderConnectionTester tester(HttpClient client) {
        return new AiProviderConnectionTester(client, new ObjectMapper(), host ->
                new InetAddress[]{InetAddress.getByName("203.0.113.10")});
    }

    private AiProviderUpdate provider(String type, String baseUrl, String anthropicVersion) {
        return new AiProviderUpdate(3L, "provider", type, baseUrl, null,
                anthropicVersion, null, true, null);
    }
}
