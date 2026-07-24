package org.oagi.score.gateway.http.configuration.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreOpenAiResponsesChatModelTest {

    @Test
    void sendsReasoningRequestsThroughResponsesInsteadOfChatCompletions() {
        OpenAIClient client = mock(OpenAIClient.class);
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ResponseService service = mock(ResponseService.class);
        when(client.responses()).thenReturn(service);
        when(service.create(any(ResponseCreateParams.class))).thenReturn(response());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("gpt-5.6-terra")
                .reasoningEffort("high")
                .build();
        ScoreOpenAiResponsesChatModel model = new ScoreOpenAiResponsesChatModel(
                client, asyncClient, options, "https://api.openai.com/v1",
                new OpenAiResponsesRequestMapper(),
                new OpenAiResponsesResponseMapper());

        var result = model.call(new Prompt("hello"));

        assertThat(result.getResult().getOutput().getText()).isEqualTo("hello back");
        verify(client).responses();
        verify(service).create(any(ResponseCreateParams.class));
        verify(client, never()).chat();
    }

    private Response response() {
        ResponseOutputMessage message = ResponseOutputMessage.builder()
                .id("msg_1")
                .status(ResponseOutputMessage.Status.COMPLETED)
                .addContent(ResponseOutputText.builder()
                        .text("hello back")
                        .annotations(List.of())
                        .build())
                .build();
        return Response.builder()
                .id("resp_1")
                .createdAt(1)
                .error(Optional.empty())
                .incompleteDetails(Optional.empty())
                .instructions(Optional.empty())
                .metadata(Optional.empty())
                .model(ChatModel.of("gpt-5.6-terra"))
                .output(List.of(ResponseOutputItem.ofMessage(message)))
                .parallelToolCalls(true)
                .temperature(Optional.empty())
                .toolChoice(ToolChoiceOptions.AUTO)
                .tools(List.of())
                .topP(Optional.empty())
                .status(ResponseStatus.COMPLETED)
                .build();
    }
}
