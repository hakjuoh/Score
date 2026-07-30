package org.oagi.score.gateway.http.api.ai_management.execution;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Predicate;

/** Extracts visible and private reasoning text from Spring AI responses. */
public final class SpringAiResponseContent {

    private SpringAiResponseContent() {
    }

    public static String visibleStreaming(ChatResponse response) {
        return response != null
                ? visible(response.getResults(), text -> text != null && !text.isEmpty()) : "";
    }

    public static String visibleStored(List<Generation> generations) {
        return visible(generations, StringUtils::hasText);
    }

    private static String visible(List<Generation> generations, Predicate<String> accepted) {
        if (generations == null) return "";
        return generations.stream()
                .map(Generation::getOutput)
                .filter(output -> output != null && !isReasoning(output))
                .map(AssistantMessage::getText)
                .filter(accepted)
                .reduce("", String::concat);
    }

    public static String reasoning(List<Generation> generations) {
        if (generations == null) return null;
        return generations.stream()
                .map(Generation::getOutput)
                .filter(output -> output != null && isReasoning(output))
                .map(AssistantMessage::getText)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(null);
    }

    public static boolean isReasoning(AssistantMessage output) {
        return output != null && (output.getMetadata().containsKey("signature")
                || output.getMetadata().containsKey("data")
                || Boolean.TRUE.equals(output.getMetadata().get("thinking")));
    }
}
