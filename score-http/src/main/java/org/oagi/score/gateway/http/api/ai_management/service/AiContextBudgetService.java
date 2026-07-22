package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Conservative, provider-neutral context estimates and configured budget calculations. */
@Component
public class AiContextBudgetService {

    private static final long MODEL_CALL_OVERHEAD_TOKENS = 4096L;
    private static final long MESSAGE_OVERHEAD_TOKENS = 12L;

    private final ScoreAiModelRegistry models;

    public AiContextBudgetService(ScoreAiModelRegistry models) {
        this.models = models;
    }

    public Optional<AiContextBudget> budget(String modelName) {
        if (!StringUtils.hasText(modelName)) return Optional.empty();
        ScoreAiModelRegistry.ModelConfiguration model;
        try {
            model = models.modelConfiguration(modelName);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (model == null || model.contextBudget() == null || !model.contextBudget().configured()) {
            return Optional.empty();
        }
        ScoreAiModelRegistry.ContextBudgetDescriptor configured = model.contextBudget();
        return Optional.of(new AiContextBudget(model.name(), configured.contextWindow(),
                Objects.requireNonNullElse(configured.outputReserveTokens(), 0L),
                Objects.requireNonNullElse(configured.autoCompactThresholdTokens(),
                        configured.safeInputLimit()),
                Objects.requireNonNullElse(configured.emergencyHeadroomTokens(), 0L),
                Objects.requireNonNullElse(configured.toolOutputTokenLimit(), 32000L),
                configured.providerCompactionEnabled()));
    }

    public long estimateInputTokens(List<Message> history, Message nextMessage, String pageContext) {
        long estimate = MODEL_CALL_OVERHEAD_TOKENS + estimateText(pageContext);
        if (history != null) {
            for (Message message : history) estimate = saturatedAdd(estimate, estimateMessage(message));
        }
        return nextMessage != null ? saturatedAdd(estimate, estimateMessage(nextMessage)) : estimate;
    }

    public long estimateMessage(Message message) {
        if (message == null) return 0L;
        long estimate = MESSAGE_OVERHEAD_TOKENS + estimateText(message.getText());
        if (message instanceof MediaContent mediaContent) {
            for (Media media : mediaContent.getMedia()) {
                try {
                    byte[] data = media.getDataAsByteArray();
                    estimate = saturatedAdd(estimate, estimateBytes(data != null ? data.length : 0));
                } catch (RuntimeException exception) {
                    estimate = saturatedAdd(estimate, 2048L);
                }
            }
        }
        return estimate;
    }

    private long estimateText(String text) {
        if (!StringUtils.hasText(text)) return 0L;
        return estimateBytes(text.getBytes(StandardCharsets.UTF_8).length);
    }

    /** UTF-8 bytes / 3 is intentionally more conservative than common English token ratios. */
    private long estimateBytes(long bytes) {
        return bytes <= 0 ? 0L : Math.max(1L, (bytes + 2L) / 3L);
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

}
