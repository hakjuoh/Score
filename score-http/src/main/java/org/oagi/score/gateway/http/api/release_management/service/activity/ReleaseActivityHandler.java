package org.oagi.score.gateway.http.api.release_management.service.activity;

import jakarta.annotation.Nullable;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityExecution;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;
import org.oagi.score.gateway.http.api.release_management.controller.payload.ReleaseValidationRequest;
import org.oagi.score.gateway.http.api.release_management.controller.payload.ReleaseValidationResponse;
import org.oagi.score.gateway.http.api.release_management.controller.payload.TransitStateRequest;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseState;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps a committed UI Release command to its shared activity event. */
@Component
public final class ReleaseActivityHandler implements ScoreActivityHandler {

    private static final String CATEGORY = "release";
    private static final String ACTION = "state-change";
    private static final String NAME = CATEGORY + "." + ACTION;
    private static final String SOURCE = "SCORE_HTTP_API";

    private final ScoreActivityEventFactory events;
    private final ScoreActivityFailureClassifier failureClassifier;

    public ReleaseActivityHandler(
            ScoreActivityEventFactory events,
            ScoreActivityFailureClassifier failureClassifier) {
        this.events = events;
        this.failureClassifier = failureClassifier;
    }

    @Override
    public boolean supports(ScoreActivityInvocation invocation) {
        return CATEGORY.equals(invocation.category()) && ACTION.equals(invocation.action());
    }

    @Override
    public ScoreActivityExecution start(ScoreActivityInvocation invocation) {
        if (!supports(invocation)) {
            throw new IllegalArgumentException("Unsupported release activity: " + invocation.name());
        }
        ScoreUser requester = invocation.argument(0, ScoreUser.class);
        Transition transition = transition(invocation.arguments().get(1));
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                return !(result instanceof ReleaseValidationResponse response) || response.isSucceed();
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null) {
                    return List.of();
                }
                if (result instanceof ReleaseValidationResponse response && !response.isSucceed()) {
                    return List.of(ReleaseActivityHandler.this.failed(
                            requester, transition, ScoreActivityFailureCode.NOT_APPLIED));
                }
                return List.of(events.succeeded(
                        NAME, SOURCE, requester, targets(transition.releaseId()),
                        properties(transition.toState(), null)));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                return requester == null
                        ? List.of()
                        : List.of(ReleaseActivityHandler.this.failed(
                                requester, transition, failureClassifier.classify(failure)));
            }
        };
    }

    private ScoreActivityEvent failed(
            ScoreUser requester,
            Transition transition,
            ScoreActivityFailureCode failureCode) {
        return events.failed(
                NAME, SOURCE, requester, targets(transition.releaseId()),
                properties(transition.toState(), failureCode));
    }

    private static Transition transition(@Nullable Object request) {
        if (request instanceof TransitStateRequest value) {
            return new Transition(value.getReleaseId(), state(value.getState()));
        }
        if (request instanceof ReleaseValidationRequest value) {
            return new Transition(value.getReleaseId(), ReleaseState.Draft);
        }
        return new Transition(null, null);
    }

    @Nullable
    private static ReleaseState state(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return ReleaseState.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<ScoreActivityTarget> targets(@Nullable ReleaseId releaseId) {
        return releaseId == null
                ? List.of()
                : List.of(ScoreActivityTarget.primary("RELEASE", releaseId, null, null));
    }

    private static Map<String, Object> properties(
            @Nullable ReleaseState toState,
            @Nullable ScoreActivityFailureCode failureCode) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (toState != null) {
            properties.put("toState", toState.name());
        }
        if (failureCode == null && toState != null) {
            properties.put("completionStage",
                    toState == ReleaseState.Initialized ? "COMPLETED" : "REQUEST_ACCEPTED");
        }
        if (failureCode != null) {
            properties.put("errorCode", failureCode.name());
        }
        return properties;
    }

    private record Transition(@Nullable ReleaseId releaseId, @Nullable ReleaseState toState) {
    }
}
