package org.oagi.score.gateway.http.configuration.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.ResponseStreamEvent;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring AI ChatModel adapter for the OpenAI Responses API.
 *
 * <p>Spring AI 2.0's OpenAI ChatModel always targets Chat Completions. This
 * adapter keeps the existing ChatClient/advisor integration while using the
 * Responses endpoint required by GPT-5.6 reasoning with function tools.</p>
 */
final class ScoreOpenAiResponsesChatModel implements ChatModel {

    private final OpenAIClient client;
    private final OpenAIClientAsync asyncClient;
    private final OpenAiChatOptions options;
    private final OpenAiResponsesRequestMapper requests;
    private final OpenAiResponsesResponseMapper responses;
    private final String responsesBaseUrl;

    static ScoreOpenAiResponsesChatModel create(OpenAiChatOptions options,
                                                 String responsesBaseUrl,
                                                 Duration timeout,
                                                 boolean azure) {
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                responsesBaseUrl, options.getApiKey(), options.getCredential(),
                null, null, options.getOrganizationId(), azure,
                options.isGitHubModels(), options.getModel(), timeout,
                options.getMaxRetries(), options.getProxy(), options.getCustomHeaders(),
                ObservationRegistry.NOOP, null, List.of());
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                responsesBaseUrl, options.getApiKey(), options.getCredential(),
                null, null, options.getOrganizationId(), azure,
                options.isGitHubModels(), options.getModel(), timeout,
                options.getMaxRetries(), options.getProxy(), options.getCustomHeaders(),
                ObservationRegistry.NOOP, null, List.of());
        return new ScoreOpenAiResponsesChatModel(client, asyncClient, options,
                responsesBaseUrl, new OpenAiResponsesRequestMapper(),
                new OpenAiResponsesResponseMapper());
    }

    ScoreOpenAiResponsesChatModel(OpenAIClient client,
                                  OpenAIClientAsync asyncClient,
                                  OpenAiChatOptions options,
                                  String responsesBaseUrl,
                                  OpenAiResponsesRequestMapper requests,
                                  OpenAiResponsesResponseMapper responses) {
        this.client = Objects.requireNonNull(client, "client");
        this.asyncClient = Objects.requireNonNull(asyncClient, "asyncClient");
        this.options = Objects.requireNonNull(options, "options");
        this.responsesBaseUrl = Objects.requireNonNull(responsesBaseUrl,
                "responsesBaseUrl");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    @Override
    public OpenAiChatOptions getOptions() {
        return options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt requestPrompt = requestPrompt(prompt);
        return responses.complete(client.responses().create(requests.create(
                requestPrompt.getInstructions(), requestOptions(requestPrompt))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            Prompt requestPrompt = requestPrompt(prompt);
            var parameters = requests.create(
                    requestPrompt.getInstructions(), requestOptions(requestPrompt));
            OpenAiResponsesResponseMapper.StreamState state = responses.streamState();
            Flux<ResponseStreamEvent> events = Flux.create(sink -> {
                AsyncStreamResponse<ResponseStreamEvent> stream =
                        asyncClient.responses().createStreaming(parameters);
                sink.onDispose(stream::close);
                stream.subscribe(new AsyncStreamResponse.Handler<>() {
                    @Override
                    public void onNext(ResponseStreamEvent event) {
                        sink.next(event);
                    }

                    @Override
                    public void onComplete(Optional<Throwable> error) {
                        if (error.isPresent()) sink.error(error.orElseThrow());
                        else sink.complete();
                    }
                });
            });
            return events.handle(state::accept);
        });
    }

    String responsesBaseUrl() {
        return responsesBaseUrl;
    }

    private Prompt requestPrompt(Prompt prompt) {
        return prompt.getOptions() == null
                ? prompt.mutate().chatOptions(options).build() : prompt;
    }

    private OpenAiChatOptions requestOptions(Prompt prompt) {
        if (prompt.getOptions() instanceof OpenAiChatOptions openAi) return openAi;
        throw new IllegalArgumentException(
                "Score OpenAI Responses model requires OpenAiChatOptions.");
    }
}
