package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Publishes canonical observation events and projects their identities onto spans. */
final class AiObservationEvents {

    private final ObjectProvider<ExecutionObserver> publishers;

    AiObservationEvents(ObjectProvider<ExecutionObserver> publishers) {
        this.publishers = publishers;
    }

    ExecutionScope scope(String requestId, String conversationId, ScoreUser requester,
                         long generation, ExecutionScope.Purpose purpose) {
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && StringUtils.hasText(requester.username())
                ? requester.username() : "unknown";
        String correlatedConversation = StringUtils.hasText(conversationId)
                ? conversationId.strip() : requestId;
        return new ExecutionScope(requestId, correlatedConversation, requesterId,
                Math.max(0L, generation), purpose, List.of());
    }

    ExecutionObservation publish(String type, ExecutionScope scope,
                                 Map<String, Object> attributes) {
        return publish(type, scope, attributes, ignored -> { });
    }

    ExecutionObservation publish(String type, ExecutionScope scope,
                                 Map<String, Object> attributes,
                                 Consumer<ExecutionObservation> projection) {
        if (publishers == null) return null;
        ExecutionObserver publisher = publishers.getIfAvailable();
        if (publisher == null) return null;
        AtomicReference<ExecutionObservation> published = new AtomicReference<>();
        publisher.publish(ExecutionObservation.of(type, scope, attributes),
                published::set, projection);
        return published.get();
    }

    static void startIdentity(SpanBuilder builder, ExecutionObservation event) {
        if (event == null) return;
        Object id = event.attributes().get(ExecutionEventPublisher.EVENT_ID);
        Object sequence = event.attributes().get(ExecutionEventPublisher.EVENT_SEQUENCE);
        if (id != null) builder.setAttribute(ExecutionEventPublisher.EVENT_ID, id.toString());
        if (sequence instanceof Number number) {
            builder.setAttribute(ExecutionEventPublisher.EVENT_SEQUENCE, number.longValue());
        }
        builder.setAttribute(ExecutionEventPublisher.EVENT_OCCURRED_AT,
                event.occurredAt().toString());
        builder.setStartTimestamp(event.occurredAt());
    }

    static void startIdentity(SpanBuilder builder,
                              AiTrajectoryRecorder.ExecutionEventIdentity event) {
        if (event == null) return;
        builder.setAttribute(ExecutionEventPublisher.EVENT_ID, event.eventId());
        builder.setAttribute(ExecutionEventPublisher.EVENT_SEQUENCE, event.sequence());
        builder.setAttribute(ExecutionEventPublisher.EVENT_OCCURRED_AT,
                event.occurredAt().toString());
        builder.setStartTimestamp(event.occurredAt());
    }

    static void terminalIdentity(Span span, ExecutionObservation event) {
        if (event == null) return;
        Object id = event.attributes().get(ExecutionEventPublisher.EVENT_ID);
        Object sequence = event.attributes().get(ExecutionEventPublisher.EVENT_SEQUENCE);
        if (id != null) span.setAttribute("score.event.end.id", id.toString());
        if (sequence instanceof Number number) {
            span.setAttribute("score.event.end.sequence", number.longValue());
        }
        span.setAttribute("score.event.end.occurred_at", event.occurredAt().toString());
    }
}
