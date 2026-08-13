package org.oagi.score.gateway.http.api.cc_management.service.activity;

import jakarta.annotation.Nullable;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityExecution;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.ascc.AsccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.ascc.AsccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.CreateOagisBodResponse;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.CreateOagisVerbResponse;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bcc.BccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bcc.BccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.dt.DtUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.dt_sc.DtScUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt_sc.DtScManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records named component operations that share a lifecycle event such as {@code acc.update}. */
@Component
public final class CoreComponentOperationActivityHandler implements ScoreActivityHandler {

    private static final Set<String> ACTIONS = Set.of("create", "update", "state-change", "delete");

    private final CoreComponentActivityEventFactory events;
    private final CoreComponentActivityTargetResolver targets;
    private final RepositoryFactory repositories;
    private final ScoreActivityFailureClassifier failures;

    public CoreComponentOperationActivityHandler(
            CoreComponentActivityEventFactory events,
            CoreComponentActivityTargetResolver targets,
            RepositoryFactory repositories,
            ScoreActivityFailureClassifier failures) {
        this.events = events;
        this.targets = targets;
        this.repositories = repositories;
        this.failures = failures;
    }

    @Override
    public boolean supports(ScoreActivityInvocation invocation) {
        return !invocation.operation().isBlank() && ACTIONS.contains(invocation.action());
    }

    @Override
    public ScoreActivityExecution start(ScoreActivityInvocation invocation) {
        if (!supports(invocation)) {
            throw new IllegalArgumentException("Unsupported core component operation: " + invocation.name());
        }
        ScoreUser requester = invocation.argument(0, ScoreUser.class);
        List<ScoreActivityTarget> initialTargets = requester == null
                ? List.of() : resolveTargets(invocation, requester, null);
        Map<String, Object> properties = properties(invocation, requester);
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                if (isBatchOperation(invocation)) {
                    return allBatchItemsApplied(invocation, result);
                }
                if (result instanceof Boolean applied) {
                    return applied;
                }
                if (result instanceof CreateOagisBodResponse response) {
                    return !response.manifestIdList().isEmpty();
                }
                if (result instanceof CreateOagisVerbResponse response) {
                    return response.basedVerbAsccpManifestId() != null;
                }
                return !"create".equals(invocation.action()) || result != null;
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null) {
                    return List.of();
                }
                if (!isSuccessful(result)) {
                    return List.of(events.operationFailed(
                            invocation.category(), invocation.action(), requester,
                            initialTargets, properties, ScoreActivityFailureCode.NOT_APPLIED));
                }
                List<ScoreActivityTarget> resolved = usesInitialTargetsAfterSuccess(invocation)
                        ? initialTargets : resolveTargets(invocation, requester, result, true);
                return List.of(events.operationSucceeded(
                        invocation.category(), invocation.action(), requester,
                        resolved.isEmpty() ? initialTargets : resolved, properties));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                return requester == null ? List.of() : List.of(events.operationFailed(
                        invocation.category(), invocation.action(), requester,
                        initialTargets, properties, failureCode(invocation, failure)));
            }
        };
    }

    private Map<String, Object> properties(
            ScoreActivityInvocation invocation,
            @Nullable ScoreUser requester) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", invocation.operation());
        if ("state-change".equals(invocation.action())) {
            CcState toState = switch (invocation.operation()) {
                case "revise" -> CcState.WIP;
                case "cancel" -> requester != null && requester.isDeveloper()
                        ? CcState.Published : CcState.Production;
                default -> null;
            };
            if (toState != null) {
                properties.put("toState", toState.name());
            }
        }
        return properties;
    }

    private List<ScoreActivityTarget> resolveTargets(
            ScoreActivityInvocation invocation,
            ScoreUser requester,
            @Nullable Object result) {
        return resolveTargets(invocation, requester, result, false);
    }

    private List<ScoreActivityTarget> resolveTargets(
            ScoreActivityInvocation invocation,
            ScoreUser requester,
            @Nullable Object result,
            boolean requireDetails) {
        LinkedHashSet<TargetRef> refs = new LinkedHashSet<>();
        collectSpecialTargets(invocation, requester, result, refs);
        if (refs.isEmpty()) {
            ManifestId targetId = firstManifestId(invocation.arguments());
            if (targetId == null && result instanceof ManifestId resultId) {
                targetId = resultId;
            }
            if (targetId != null) {
                refs.add(new TargetRef(category(targetId), targetId));
            }
        }
        List<ScoreActivityTarget> resolved = new ArrayList<>();
        for (TargetRef ref : refs) {
            resolved.add(requireDetails
                    ? targets.resolve(ref.category(), requester, ref.id())
                    : targets.resolveOrReference(ref.category(), requester, ref.id()));
        }
        return List.copyOf(resolved);
    }

    private void collectSpecialTargets(
            ScoreActivityInvocation invocation,
            ScoreUser requester,
            @Nullable Object result,
            Set<TargetRef> refs) {
        Object request = invocation.arguments().size() > 1 ? invocation.arguments().get(1) : null;
        switch (invocation.operation()) {
            case "append-ascc" -> {
                if (request instanceof AsccCreateRequest value && value.accManifestId() != null) {
                    refs.add(new TargetRef("acc", value.accManifestId()));
                }
            }
            case "append-bcc" -> {
                if (request instanceof BccCreateRequest value && value.accManifestId() != null) {
                    refs.add(new TargetRef("acc", value.accManifestId()));
                }
            }
            case "discard-ascc" -> {
                if (request instanceof AsccManifestId id) addAsccOwner(requester, id, refs);
            }
            case "discard-bcc" -> {
                if (request instanceof BccManifestId id) addBccOwner(requester, id, refs);
            }
            case "update-ascc" -> list(request).stream()
                    .filter(AsccUpdateRequest.class::isInstance)
                    .map(AsccUpdateRequest.class::cast)
                    .forEach(item -> addAsccOwner(requester, item.asccManifestId(), refs));
            case "update-bcc" -> list(request).stream()
                    .filter(BccUpdateRequest.class::isInstance)
                    .map(BccUpdateRequest.class::cast)
                    .forEach(item -> addBccOwner(requester, item.bccManifestId(), refs));
            case "update-dt-sc" -> list(request).stream()
                    .filter(DtScUpdateRequest.class::isInstance)
                    .map(DtScUpdateRequest.class::cast)
                    .forEach(item -> addDtScOwner(requester, item.dtScManifestId(), refs));
            case "update-details" -> list(request).stream()
                    .filter(DtUpdateRequest.class::isInstance)
                    .map(DtUpdateRequest.class::cast)
                    .forEach(item -> refs.add(new TargetRef("dt", item.dtManifestId())));
            case "discard-dt-sc" -> {
                if (request instanceof DtScManifestId id) addDtScOwner(requester, id, refs);
            }
            case "refactor-ascc", "refactor-bcc" -> {
                if (invocation.arguments().size() > 2
                        && invocation.arguments().get(2) instanceof AccManifestId id) {
                    refs.add(new TargetRef("acc", id));
                }
            }
            case "generate-bod" -> {
                if (result instanceof CreateOagisBodResponse response) {
                    response.manifestIdList().forEach(id -> refs.add(new TargetRef("asccp", id)));
                }
            }
            case "generate-verb" -> {
                if (result instanceof CreateOagisVerbResponse response) {
                    refs.add(new TargetRef("asccp", response.basedVerbAsccpManifestId()));
                }
            }
            default -> {
                // The first manifest argument/result covers ordinary component operations.
            }
        }
    }

    private void addAsccOwner(ScoreUser requester, AsccManifestId id, Set<TargetRef> refs) {
        if (id == null) return;
        var association = repositories.accQueryRepository(requester).getAsccSummary(id);
        if (association != null) {
            refs.add(new TargetRef("acc", association.fromAccManifestId()));
        }
    }

    private void addBccOwner(ScoreUser requester, BccManifestId id, Set<TargetRef> refs) {
        if (id == null) return;
        var association = repositories.accQueryRepository(requester).getBccSummary(id);
        if (association != null) {
            refs.add(new TargetRef("acc", association.fromAccManifestId()));
        }
    }

    private void addDtScOwner(ScoreUser requester, DtScManifestId id, Set<TargetRef> refs) {
        if (id == null) return;
        var component = repositories.dtQueryRepository(requester).getDtScSummary(id);
        if (component != null) {
            refs.add(new TargetRef("dt", component.ownerDtManifestId()));
        }
    }

    private static List<?> list(@Nullable Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private ScoreActivityFailureCode failureCode(
            ScoreActivityInvocation invocation, Throwable failure) {
        return isBatchOperation(invocation) && list(invocation.arguments().get(1)).size() > 1
                ? ScoreActivityFailureCode.BATCH_ROLLED_BACK
                : failures.classify(failure);
    }

    private static boolean isBatchOperation(ScoreActivityInvocation invocation) {
        return Set.of("update-ascc", "update-bcc", "update-details", "update-dt-sc")
                .contains(invocation.operation());
    }

    private static boolean usesInitialTargetsAfterSuccess(ScoreActivityInvocation invocation) {
        return "delete".equals(invocation.action())
                || invocation.operation().startsWith("discard-");
    }

    private static boolean allBatchItemsApplied(
            ScoreActivityInvocation invocation, @Nullable Object result) {
        List<?> requests = list(invocation.arguments().get(1));
        if (requests.isEmpty() || !(result instanceof List<?> results)) {
            return false;
        }
        if (requests.size() != results.size()
                || results.stream().anyMatch(item -> !(item instanceof ManifestId))) {
            return false;
        }
        Map<ManifestId, Integer> remaining = new HashMap<>();
        results.stream().filter(ManifestId.class::isInstance).map(ManifestId.class::cast)
                .forEach(id -> remaining.merge(id, 1, Integer::sum));
        for (Object request : requests) {
            ManifestId expected = batchTargetId(request);
            int count = expected == null ? 0 : remaining.getOrDefault(expected, 0);
            if (count == 0) {
                return false;
            }
            remaining.put(expected, count - 1);
        }
        return remaining.values().stream().allMatch(count -> count == 0);
    }

    @Nullable
    private static ManifestId batchTargetId(@Nullable Object request) {
        if (request instanceof AsccUpdateRequest value) return value.asccManifestId();
        if (request instanceof BccUpdateRequest value) return value.bccManifestId();
        if (request instanceof DtUpdateRequest value) return value.dtManifestId();
        if (request instanceof DtScUpdateRequest value) return value.dtScManifestId();
        return null;
    }

    @Nullable
    private static ManifestId firstManifestId(List<Object> arguments) {
        return arguments.stream().filter(ManifestId.class::isInstance)
                .map(ManifestId.class::cast).findFirst().orElse(null);
    }

    private static String category(ManifestId id) {
        if (id instanceof AccManifestId) return "acc";
        if (id instanceof AsccManifestId) return "ascc";
        if (id instanceof BccManifestId) return "bcc";
        if (id instanceof DtManifestId) return "dt";
        if (id instanceof DtScManifestId) return "dt-sc";
        String simpleName = id.getClass().getSimpleName();
        if (simpleName.startsWith("Asccp")) return "asccp";
        if (simpleName.startsWith("Bccp")) return "bccp";
        throw new IllegalArgumentException("Unsupported activity target ID: " + simpleName);
    }

    private record TargetRef(String category, ManifestId id) {
    }
}
