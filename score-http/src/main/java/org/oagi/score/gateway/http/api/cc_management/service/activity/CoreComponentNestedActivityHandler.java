package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityExecution;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Emits one child event for an atomic component change inside a larger user operation. */
@Component
public final class CoreComponentNestedActivityHandler implements ScoreActivityHandler {

    private static final Set<String> CATEGORIES = Set.of("ascc", "bcc", "dt-sc");
    private static final Set<String> ACTIONS = Set.of("create", "update", "delete");

    private final CoreComponentActivityEventFactory events;
    private final CoreComponentActivityTargetResolver targets;
    private final ScoreActivityFailureClassifier failures;

    public CoreComponentNestedActivityHandler(
            CoreComponentActivityEventFactory events,
            CoreComponentActivityTargetResolver targets,
            ScoreActivityFailureClassifier failures) {
        this.events = events;
        this.targets = targets;
        this.failures = failures;
    }

    @Override
    public boolean supports(ScoreActivityInvocation invocation) {
        return CATEGORIES.contains(invocation.category()) && ACTIONS.contains(invocation.action());
    }

    @Override
    public ScoreActivityExecution start(ScoreActivityInvocation invocation) {
        ScoreUser requester = invocation.argument(0, ScoreUser.class);
        ManifestId inputId = "create".equals(invocation.action())
                ? null : invocation.argument(1, ManifestId.class);
        List<ScoreActivityTarget> initial = requester == null || inputId == null
                ? List.of() : List.of(targets.resolveOrReference(invocation.category(), requester, inputId));
        Map<String, Object> properties = invocation.operation().isBlank()
                ? Map.of() : Map.of("operation", invocation.operation());
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                return result instanceof Boolean applied
                        ? applied : !"create".equals(invocation.action()) || result instanceof ManifestId;
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null) {
                    return List.of();
                }
                if (!isSuccessful(result)) {
                    return List.of(events.operationFailed(
                            invocation.category(), invocation.action(), requester,
                            initial, properties, ScoreActivityFailureCode.NOT_APPLIED));
                }
                List<ScoreActivityTarget> completed = initial;
                if ("create".equals(invocation.action()) && result instanceof ManifestId resultId) {
                    completed = List.of(targets.resolve(invocation.category(), requester, resultId));
                } else if ("update".equals(invocation.action()) && inputId != null) {
                    completed = List.of(targets.resolve(invocation.category(), requester, inputId));
                }
                return List.of(events.operationSucceeded(
                        invocation.category(), invocation.action(), requester, completed, properties));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                return requester == null ? List.of() : List.of(events.operationFailed(
                        invocation.category(), invocation.action(), requester,
                        initial, properties, failures.classify(failure)));
            }
        };
    }
}
