package org.oagi.score.gateway.http.api.cc_management.service.activity;

import jakarta.annotation.Nullable;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the source-independent wire shape shared by component activities. */
@Component
public final class CoreComponentActivityEventFactory {

    static final String CREATE = "create";
    static final String UPDATE = "update";
    static final String STATE_CHANGE = "state-change";
    static final String DELETE = "delete";

    private static final String SOURCE = "SCORE_HTTP_API";
    private static final Set<String> CATEGORIES = Set.of(
            "acc", "asccp", "bccp", "dt", "code-list", "agency-id-list");

    private final ScoreActivityEventFactory events;
    private final CoreComponentActivityTargetResolver targetResolver;

    public CoreComponentActivityEventFactory(
            ScoreActivityEventFactory events,
            CoreComponentActivityTargetResolver targetResolver) {
        this.events = events;
        this.targetResolver = targetResolver;
    }

    public ScoreActivityEvent created(
            String category,
            ScoreUser requester,
            ManifestId targetId) {
        return events.succeeded(
                name(category, CREATE), SOURCE, requester,
                successfulTarget(category, requester, targetId), Map.of());
    }

    public ScoreActivityEvent createFailed(
            String category,
            ScoreUser requester,
            ScoreActivityFailureCode failureCode) {
        return failed(category, CREATE, requester, null, failureCode, Map.of());
    }

    public ScoreActivityEvent updated(
            String category,
            ScoreUser requester,
            ManifestId targetId,
            List<String> requestedFields) {
        return events.succeeded(
                name(category, UPDATE),
                SOURCE,
                requester,
                successfulTarget(category, requester, targetId),
                Map.of("requestedFields", requestedFields));
    }

    public ScoreActivityEvent updateFailed(
            String category,
            ScoreUser requester,
            @Nullable ManifestId targetId,
            List<String> requestedFields,
            ScoreActivityFailureCode failureCode) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("errorCode", failureCode.name());
        if (!requestedFields.isEmpty()) {
            properties.put("requestedFields", requestedFields);
        }
        return events.failed(
                name(category, UPDATE), SOURCE, requester, target(category, targetId), properties);
    }

    public ScoreActivityEvent stateChanged(
            String category,
            ScoreUser requester,
            ManifestId targetId,
            CcState toState) {
        return events.succeeded(
                name(category, STATE_CHANGE),
                SOURCE,
                requester,
                successfulTarget(category, requester, targetId),
                Map.of("toState", toState.name()));
    }

    public ScoreActivityEvent stateChangeFailed(
            String category,
            ScoreUser requester,
            @Nullable ManifestId targetId,
            @Nullable CcState toState,
            ScoreActivityFailureCode failureCode) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (toState != null) {
            properties.put("toState", toState.name());
        }
        return failed(category, STATE_CHANGE, requester, targetId, failureCode, properties);
    }

    public ScoreActivityEvent deleted(
            String category,
            ScoreUser requester,
            ManifestId targetId) {
        return events.succeeded(
                name(category, DELETE), SOURCE, requester, target(category, targetId), Map.of());
    }

    public ScoreActivityEvent deleteFailed(
            String category,
            ScoreUser requester,
            @Nullable ManifestId targetId,
            ScoreActivityFailureCode failureCode) {
        return failed(category, DELETE, requester, targetId, failureCode, Map.of());
    }

    public ScoreActivityEvent operationSucceeded(
            String category,
            String action,
            ScoreUser requester,
            List<ScoreActivityTarget> targets,
            Map<String, Object> properties) {
        return events.succeeded(name(category, action), SOURCE, requester, targets, properties);
    }

    public ScoreActivityEvent operationFailed(
            String category,
            String action,
            ScoreUser requester,
            List<ScoreActivityTarget> targets,
            Map<String, Object> properties,
            ScoreActivityFailureCode failureCode) {
        Map<String, Object> failureProperties = new LinkedHashMap<>(properties);
        failureProperties.put("errorCode", failureCode.name());
        return events.failed(name(category, action), SOURCE, requester, targets, failureProperties);
    }

    private ScoreActivityEvent failed(
            String category,
            String action,
            ScoreUser requester,
            @Nullable ManifestId targetId,
            ScoreActivityFailureCode failureCode,
            Map<String, Object> additionalProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(additionalProperties);
        properties.put("errorCode", failureCode.name());
        return events.failed(
                name(category, action), SOURCE, requester, target(category, targetId), properties);
    }

    private static String name(String category, String action) {
        if (!CATEGORIES.contains(category)
                && !Set.of("ascc", "bcc", "dt-sc", "oagis-bod", "oagis-verb").contains(category)) {
            throw new IllegalArgumentException("Unsupported core component category: " + category);
        }
        return category + "." + action;
    }

    private static List<ScoreActivityTarget> target(
            String category,
            @Nullable ManifestId targetId) {
        if (targetId == null) {
            return List.of();
        }
        return List.of(ScoreActivityTarget.primary(
                targetType(category), targetId, null, null));
    }

    private List<ScoreActivityTarget> successfulTarget(
            String category,
            ScoreUser requester,
            ManifestId targetId) {
        return List.of(targetResolver.resolve(category, requester, targetId));
    }

    private static String targetType(String category) {
        return switch (category) {
            case "code-list" -> "CODE_LIST";
            case "agency-id-list" -> "AGENCY_ID_LIST";
            default -> category.toUpperCase();
        };
    }
}
