package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes a trajectory step at the canonical ordered execution-event boundary. */
public final class TrajectoryStepAppender {

    private final ExecutionObserver observer;

    public TrajectoryStepAppender(ExecutionObserver observer) {
        this.observer = observer != null ? observer : ExecutionObserver.noop();
    }

    public void append(Command command) {
        Objects.requireNonNull(command, "command");
        AiChatConversationRepository repository = command.repository();
        ScoreUser requester = command.requester();
        String conversationId = command.conversationId();
        AiChatTrajectoryStep step = command.step();
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(step, "step");
        if (!StringUtils.hasText(step.requestId())) {
            throw new IllegalArgumentException(
                    "A request ID is required for an AI trajectory event.");
        }
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && StringUtils.hasText(requester.username())
                ? requester.username() : "unknown";
        ExecutionScope scope = new ExecutionScope(step.requestId(), conversationId,
                requesterId, Math.max(0L, command.generation()),
                Objects.requireNonNull(command.purpose(), "purpose"), List.of());
        Map<String, Object> eventAttributes = new LinkedHashMap<>(
                command.attributes() != null ? command.attributes() : Map.of());
        eventAttributes.put("message_kind", step.messageKind());
        eventAttributes.put("source", step.source());
        Runnable completion = command.afterAppend() != null ? command.afterAppend() : () -> { };
        observer.publish(ExecutionObservation.of(
                "trajectory." + step.messageKind(), scope, eventAttributes), event -> {
            Map<String, Object> extra = new LinkedHashMap<>(
                    step.extra() != null ? step.extra() : Map.of());
            ExecutionEventIdentity.copyAttributes(event, extra);
            repository.append(conversationId, new AiChatTrajectoryStep(
                    step.requestId(), step.source(), step.messageKind(), step.visibility(),
                    step.message(), step.reasoningContent(), step.modelName(), step.reasoningEffort(),
                    step.toolCalls(), step.observation(), step.metrics(), Map.copyOf(extra),
                    step.llmCallCount(), step.isCopiedContext(), event.occurredAt()));
            completion.run();
        });
    }

    /** Complete command for one canonical trajectory write. */
    public record Command(AiChatConversationRepository repository,
                          ScoreUser requester,
                          String conversationId,
                          AiChatTrajectoryStep step,
                          ExecutionScope.Purpose purpose,
                          Map<String, Object> attributes,
                          Runnable afterAppend,
                          long generation) {
    }
}
