package org.oagi.score.gateway.http.api.cc_management.service.activity;

import jakarta.annotation.Nullable;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityExecution;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.acc.AccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.AsccpUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bccp.BccpUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps component command outcomes to one shared activity policy. */
@Component
public final class CoreComponentActivityHandler implements ScoreActivityHandler {

    private static final Set<String> LIFECYCLE_CATEGORIES = Set.of("acc", "asccp", "bccp");
    private static final Set<String> STATE_CHANGE_CATEGORIES = Set.of(
            "acc", "asccp", "bccp", "dt", "code-list", "agency-id-list");
    private static final Set<String> ACTIONS = Set.of(
            CoreComponentActivityEventFactory.CREATE,
            CoreComponentActivityEventFactory.UPDATE,
            CoreComponentActivityEventFactory.STATE_CHANGE,
            CoreComponentActivityEventFactory.DELETE);

    private final CoreComponentActivityEventFactory events;
    private final ScoreActivityFailureClassifier failureClassifier;

    public CoreComponentActivityHandler(
            CoreComponentActivityEventFactory events,
            ScoreActivityFailureClassifier failureClassifier) {
        this.events = events;
        this.failureClassifier = failureClassifier;
    }

    @Override
    public boolean supports(ScoreActivityInvocation invocation) {
        return (LIFECYCLE_CATEGORIES.contains(invocation.category())
                && ACTIONS.contains(invocation.action()))
                || (STATE_CHANGE_CATEGORIES.contains(invocation.category())
                && CoreComponentActivityEventFactory.STATE_CHANGE.equals(invocation.action()));
    }

    @Override
    public ScoreActivityExecution start(ScoreActivityInvocation invocation) {
        if (!supports(invocation)) {
            throw new IllegalArgumentException("Unsupported core component activity: " + invocation.name());
        }
        ScoreUser requester = invocation.argument(0, ScoreUser.class);
        return switch (invocation.action()) {
            case CoreComponentActivityEventFactory.CREATE -> create(invocation.category(), requester);
            case CoreComponentActivityEventFactory.UPDATE -> update(invocation, requester);
            case CoreComponentActivityEventFactory.STATE_CHANGE -> stateChange(invocation, requester);
            case CoreComponentActivityEventFactory.DELETE -> delete(invocation, requester);
            default -> throw new IllegalStateException("Unsupported activity: " + invocation.name());
        };
    }

    private ScoreActivityExecution create(String category, @Nullable ScoreUser requester) {
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                return result instanceof ManifestId;
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null) {
                    return List.of();
                }
                return result instanceof ManifestId targetId
                        ? List.of(events.created(category, requester, targetId))
                        : List.of(events.createFailed(
                                category, requester, ScoreActivityFailureCode.NOT_APPLIED));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                return requester == null
                        ? List.of()
                        : List.of(events.createFailed(
                                category, requester, failureClassifier.classify(failure)));
            }
        };
    }

    private ScoreActivityExecution update(
            ScoreActivityInvocation invocation,
            @Nullable ScoreUser requester) {
        Object requestArgument = invocation.arguments().get(1);
        List<UpdateItem> items = updateItems(invocation.category(), invocation.arguments());
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                if (result instanceof List<?> resultList) {
                    return !items.isEmpty() && allItemsUpdated(items, resultList);
                }
                return Boolean.TRUE.equals(result)
                        && items.size() == 1
                        && items.getFirst().targetId() != null;
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null || (requestArgument == null && result instanceof List<?>)) {
                    return List.of();
                }
                if (result instanceof List<?> resultList) {
                    return batchUpdated(invocation.category(), requester, items, resultList);
                }
                if (items.isEmpty()) {
                    return List.of();
                }
                UpdateItem item = items.getFirst();
                return List.of(Boolean.TRUE.equals(result) && item.targetId() != null
                        ? events.updated(
                                invocation.category(), requester, item.targetId(), item.requestedFields())
                        : events.updateFailed(
                                invocation.category(), requester, item.targetId(), item.requestedFields(),
                                ScoreActivityFailureCode.NOT_APPLIED));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                if (requester == null) {
                    return List.of();
                }
                ScoreActivityFailureCode failureCode = items.size() > 1
                        ? ScoreActivityFailureCode.BATCH_ROLLED_BACK
                        : failureClassifier.classify(failure);
                return items.stream()
                        .map(item -> events.updateFailed(
                                invocation.category(), requester, item.targetId(), item.requestedFields(),
                                failureCode))
                        .toList();
            }
        };
    }

    private ScoreActivityExecution stateChange(
            ScoreActivityInvocation invocation,
            @Nullable ScoreUser requester) {
        ManifestId targetId = invocation.argument(1, ManifestId.class);
        CcState toState = invocation.argument(2, CcState.class);
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                return Boolean.TRUE.equals(result) && targetId != null && toState != null;
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null) {
                    return List.of();
                }
                return Boolean.TRUE.equals(result) && targetId != null && toState != null
                        ? List.of(events.stateChanged(
                                invocation.category(), requester, targetId, toState))
                        : List.of(events.stateChangeFailed(
                                invocation.category(), requester, targetId, toState,
                                ScoreActivityFailureCode.NOT_APPLIED));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                return requester == null
                        ? List.of()
                        : List.of(events.stateChangeFailed(
                                invocation.category(), requester, targetId, toState,
                                failureClassifier.classify(failure)));
            }
        };
    }

    private ScoreActivityExecution delete(
            ScoreActivityInvocation invocation,
            @Nullable ScoreUser requester) {
        ManifestId targetId = invocation.argument(1, ManifestId.class);
        return new ScoreActivityExecution() {
            @Override
            public boolean isSuccessful(Object result) {
                return Boolean.TRUE.equals(result) && targetId != null;
            }

            @Override
            public List<ScoreActivityEvent> succeeded(Object result) {
                if (requester == null) {
                    return List.of();
                }
                return Boolean.TRUE.equals(result) && targetId != null
                        ? List.of(events.deleted(invocation.category(), requester, targetId))
                        : List.of(events.deleteFailed(
                                invocation.category(), requester, targetId,
                                ScoreActivityFailureCode.NOT_APPLIED));
            }

            @Override
            public List<ScoreActivityEvent> failed(Throwable failure) {
                return requester == null
                        ? List.of()
                        : List.of(events.deleteFailed(
                                invocation.category(), requester, targetId,
                                failureClassifier.classify(failure)));
            }
        };
    }

    private List<ScoreActivityEvent> batchUpdated(
            String category,
            ScoreUser requester,
            List<UpdateItem> items,
            List<?> resultList) {
        Map<ManifestId, Integer> remainingSuccesses = new HashMap<>();
        resultList.stream()
                .filter(ManifestId.class::isInstance)
                .map(ManifestId.class::cast)
                .forEach(id -> remainingSuccesses.merge(id, 1, Integer::sum));

        List<ScoreActivityEvent> activityEvents = new ArrayList<>();
        for (UpdateItem item : items) {
            int remaining = remainingSuccesses.getOrDefault(item.targetId(), 0);
            if (item.targetId() != null && remaining > 0) {
                remainingSuccesses.put(item.targetId(), remaining - 1);
                activityEvents.add(events.updated(
                        category, requester, item.targetId(), item.requestedFields()));
            } else {
                activityEvents.add(events.updateFailed(
                        category, requester, item.targetId(), item.requestedFields(),
                        ScoreActivityFailureCode.NOT_APPLIED));
            }
        }
        return List.copyOf(activityEvents);
    }

    private static boolean allItemsUpdated(List<UpdateItem> items, List<?> resultList) {
        Map<ManifestId, Integer> remainingSuccesses = new HashMap<>();
        resultList.stream()
                .filter(ManifestId.class::isInstance)
                .map(ManifestId.class::cast)
                .forEach(id -> remainingSuccesses.merge(id, 1, Integer::sum));
        for (UpdateItem item : items) {
            if (item.targetId() == null) {
                return false;
            }
            int remaining = remainingSuccesses.getOrDefault(item.targetId(), 0);
            if (remaining == 0) {
                return false;
            }
            remainingSuccesses.put(item.targetId(), remaining - 1);
        }
        return true;
    }

    private static List<UpdateItem> updateItems(String category, List<Object> arguments) {
        Object requestArgument = arguments.get(1);
        if (requestArgument instanceof List<?> requests) {
            return requests.stream().map(request -> updateItem(category, request)).toList();
        }
        if (requestArgument instanceof ManifestId targetId && arguments.size() > 2) {
            String requestedField = switch (category) {
                case "asccp" -> "roleOfAccManifestId";
                case "bccp" -> "dtManifestId";
                default -> throw new IllegalArgumentException(
                        "Unsupported direct update arguments for " + category);
            };
            return List.of(new UpdateItem(targetId, List.of(requestedField)));
        }
        return List.of(updateItem(category, requestArgument));
    }

    private static UpdateItem updateItem(String category, @Nullable Object request) {
        return switch (category) {
            case "acc" -> accUpdateItem(request);
            case "asccp" -> asccpUpdateItem(request);
            case "bccp" -> bccpUpdateItem(request);
            default -> throw new IllegalArgumentException("Unsupported update category: " + category);
        };
    }

    private static UpdateItem accUpdateItem(@Nullable Object request) {
        if (!(request instanceof AccUpdateRequest value)) {
            return UpdateItem.empty();
        }
        List<String> fields = new ArrayList<>();
        addIfNotNull(fields, "objectClassTerm", value.objectClassTerm());
        addIfNotNull(fields, "componentType", value.componentType());
        addIfNotNull(fields, "definition", value.definition());
        addIfNotNull(fields, "definitionSource", value.definitionSource());
        addIfNotNull(fields, "isAbstract", value.isAbstract());
        addIfNotNull(fields, "deprecated", value.deprecated());
        fields.add("namespaceId");
        return new UpdateItem(value.accManifestId(), List.copyOf(fields));
    }

    private static UpdateItem asccpUpdateItem(@Nullable Object request) {
        if (!(request instanceof AsccpUpdateRequest value)) {
            return UpdateItem.empty();
        }
        List<String> fields = new ArrayList<>();
        addIfNotNull(fields, "propertyTerm", value.propertyTerm());
        addIfNotNull(fields, "definition", value.definition());
        addIfNotNull(fields, "definitionSource", value.definitionSource());
        addIfNotNull(fields, "reusable", value.reusable());
        addIfNotNull(fields, "deprecated", value.deprecated());
        addIfNotNull(fields, "nillable", value.nillable());
        fields.add("namespaceId");
        return new UpdateItem(value.asccpManifestId(), List.copyOf(fields));
    }

    private static UpdateItem bccpUpdateItem(@Nullable Object request) {
        if (!(request instanceof BccpUpdateRequest value)) {
            return UpdateItem.empty();
        }
        List<String> fields = new ArrayList<>();
        addIfNotNull(fields, "propertyTerm", value.propertyTerm());
        addIfNotNull(fields, "nillable", value.nillable());
        addIfNotNull(fields, "deprecated", value.deprecated());
        fields.add("namespaceId");
        addIfNotNull(fields, "defaultValue", value.defaultValue());
        addIfNotNull(fields, "fixedValue", value.fixedValue());
        addIfNotNull(fields, "definition", value.definition());
        addIfNotNull(fields, "definitionSource", value.definitionSource());
        return new UpdateItem(value.bccpManifestId(), List.copyOf(fields));
    }

    private static void addIfNotNull(List<String> fields, String field, @Nullable Object value) {
        if (value != null) {
            fields.add(field);
        }
    }

    private record UpdateItem(@Nullable ManifestId targetId, List<String> requestedFields) {
        private static UpdateItem empty() {
            return new UpdateItem(null, List.of());
        }
    }
}
