package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityActor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Creates the shared event envelope used by every SCORE feature and runtime. */
public final class ScoreActivityEventFactory {

    private final Clock clock;
    private final ScoreActivityContextProvider contextProvider;

    public ScoreActivityEventFactory(Clock clock, ScoreActivityContextProvider contextProvider) {
        this.clock = requireNonNull(clock, "clock must not be null");
        this.contextProvider = requireNonNull(contextProvider, "contextProvider must not be null");
    }

    public ScoreActivityEvent succeeded(
            String name,
            String source,
            ScoreUser requester,
            List<ScoreActivityTarget> targets,
            Map<String, Object> properties) {
        return create(name, source, ScoreActivityEvent.SUCCEEDED, requester, targets, properties);
    }

    public ScoreActivityEvent failed(
            String name,
            String source,
            ScoreUser requester,
            List<ScoreActivityTarget> targets,
            Map<String, Object> properties) {
        return create(name, source, ScoreActivityEvent.FAILED, requester, targets, properties);
    }

    private ScoreActivityEvent create(
            String name,
            String source,
            String outcome,
            ScoreUser requester,
            List<ScoreActivityTarget> targets,
            Map<String, Object> properties) {
        return new ScoreActivityEvent(
                ScoreActivityEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                Instant.now(clock),
                name,
                source,
                outcome,
                ScoreActivityActor.from(requester),
                targets,
                properties,
                currentContext());
    }

    private ScoreActivityContext currentContext() {
        try {
            ScoreActivityContext context = contextProvider.currentContext();
            return context == null ? ScoreActivityContext.empty() : context;
        } catch (RuntimeException ignored) {
            // Trace correlation is optional and must never prevent activity delivery.
            return ScoreActivityContext.empty();
        }
    }
}
