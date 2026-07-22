package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiPendingTool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Tracks failed calls within one request and explains a model-initiated retry. */
public final class AiToolRetryTracker {

    private final Map<String, Deque<FailedAttempt>> failuresByTool = new HashMap<>();

    public AiToolRetryTracker() {
    }

    public void failed(AiPendingTool tool) {
        failuresByTool.computeIfAbsent(tool.name(), ignored -> new ArrayDeque<>())
                .addLast(new FailedAttempt(tool.observations().stepId(), tool.arguments()));
    }

    public Optional<RetryNotice> retry(AiPendingTool tool, boolean modelAlreadyNarrated) {
        Deque<FailedAttempt> failures = failuresByTool.get(tool.name());
        if (failures == null) {
            return Optional.empty();
        }
        FailedAttempt retryOf = failures.stream()
                .filter(failure -> failure.modelStepId() != tool.observations().stepId())
                .findFirst()
                .orElse(null);
        if (retryOf == null) {
            return Optional.empty();
        }
        failures.remove(retryOf);
        if (failures.isEmpty()) {
            failuresByTool.remove(tool.name());
        }
        if (modelAlreadyNarrated) {
            return Optional.empty();
        }
        return Optional.of(new RetryNotice(tool.name(),
                !Objects.equals(retryOf.arguments(), tool.arguments())));
    }

    private record FailedAttempt(long modelStepId, Object arguments) {
    }

    public record RetryNotice(String toolName, boolean argumentsCorrected) {
    }
}
