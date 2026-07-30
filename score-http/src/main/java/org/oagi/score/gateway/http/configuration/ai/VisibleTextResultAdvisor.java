package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiResponseContent;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Makes the user-facing Anthropic text/tool generation the primary result.
 *
 * Anthropic returns thinking blocks as separate generations before the final text block.
 * ChatClient's content/entity projections read only the first generation, so an adaptive
 * thinking response can otherwise be mistaken for an empty answer.
 */
public final class VisibleTextResultAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return visibleResponse(chain.nextCall(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).map(this::visibleResponse);
    }

    private ChatClientResponse visibleResponse(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults().size() < 2) {
            return response;
        }

        Generation primary = chatResponse.getResults().stream()
                .filter(this::isVisibleResult)
                .reduce((first, second) -> second)
                .orElse(null);
        if (primary == null || primary == chatResponse.getResult()) {
            return response;
        }

        List<Generation> ordered = new ArrayList<>(chatResponse.getResults().size());
        ordered.add(primary);
        chatResponse.getResults().stream().filter(generation -> generation != primary).forEach(ordered::add);
        return response.mutate()
                .chatResponse(ChatResponse.builder().from(chatResponse).generations(ordered).build())
                .build();
    }

    private boolean isVisibleResult(Generation generation) {
        AssistantMessage output = generation.getOutput();
        return output.hasToolCalls() || (StringUtils.hasText(output.getText())
                && !SpringAiResponseContent.isReasoning(output));
    }

    @Override
    public String getName() {
        return "connectCenter visible response";
    }

    @Override
    public int getOrder() {
        // Run inside ToolCallingAdvisor so every model/tool-loop response is normalized.
        return Ordered.HIGHEST_PRECEDENCE + 400;
    }
}
