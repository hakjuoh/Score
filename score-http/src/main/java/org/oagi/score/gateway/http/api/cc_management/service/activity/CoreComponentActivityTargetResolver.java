package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt_sc.DtScManifestId;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListManifestId;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Loads the stable identity displayed for a successfully resolved core-component target. */
@Component
public final class CoreComponentActivityTargetResolver {

    private final RepositoryFactory repositoryFactory;

    public CoreComponentActivityTargetResolver(RepositoryFactory repositoryFactory) {
        this.repositoryFactory = repositoryFactory;
    }

    public ScoreActivityTarget resolve(String category, ScoreUser requester, ManifestId targetId) {
        requireNonNull(requester, "requester must not be null");
        requireNonNull(targetId, "targetId must not be null");
        return switch (category) {
            case "acc" -> {
                var id = requireType(targetId, AccManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.accQueryRepository(requester).getAccSummary(id),
                        "ACC activity target does not exist: " + id);
                yield target("ACC", id, target.guid(), target.objectClassTerm());
            }
            case "asccp" -> {
                var id = requireType(targetId, AsccpManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.asccpQueryRepository(requester).getAsccpSummary(id),
                        "ASCCP activity target does not exist: " + id);
                yield target("ASCCP", id, target.guid(), target.propertyTerm());
            }
            case "bccp" -> {
                var id = requireType(targetId, BccpManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.bccpQueryRepository(requester).getBccpSummary(id),
                        "BCCP activity target does not exist: " + id);
                yield target("BCCP", id, target.guid(), target.propertyTerm());
            }
            case "dt" -> {
                var id = requireType(targetId, DtManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.dtQueryRepository(requester).getDtSummary(id),
                        "DT activity target does not exist: " + id);
                yield target("DT", id, target.guid(), target.dataTypeTerm());
            }
            case "code-list" -> {
                var id = requireType(targetId, CodeListManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.codeListQueryRepository(requester).getCodeListSummary(id),
                        "Code list activity target does not exist: " + id);
                yield target("CODE_LIST", id, target.guid(), target.name());
            }
            case "agency-id-list" -> {
                var id = requireType(targetId, AgencyIdListManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.agencyIdListQueryRepository(requester).getAgencyIdListSummary(id),
                        "Agency ID list activity target does not exist: " + id);
                yield target("AGENCY_ID_LIST", id, target.guid(), target.name());
            }
            case "ascc" -> {
                var id = requireType(targetId, AsccManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.accQueryRepository(requester).getAsccSummary(id),
                        "ASCC activity target does not exist: " + id);
                yield target("ASCC", id, target.guid(), target.den());
            }
            case "bcc" -> {
                var id = requireType(targetId, BccManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.accQueryRepository(requester).getBccSummary(id),
                        "BCC activity target does not exist: " + id);
                yield target("BCC", id, target.guid(), target.den());
            }
            case "dt-sc" -> {
                var id = requireType(targetId, DtScManifestId.class, category);
                var target = requireNonNull(
                        repositoryFactory.dtQueryRepository(requester).getDtScSummary(id),
                        "DT_SC activity target does not exist: " + id);
                String name = String.join(". ", java.util.stream.Stream.of(
                                target.objectClassTerm(), target.propertyTerm(), target.representationTerm())
                        .filter(value -> value != null && !value.isBlank())
                        .toList());
                yield target("DT_SC", id, target.guid(), name);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported core component category: " + category);
        };
    }

    /** Returns an identity-only target when details cannot be loaded (notably for failed commands). */
    public ScoreActivityTarget resolveOrReference(
            String category, ScoreUser requester, ManifestId targetId) {
        try {
            return resolve(category, requester, targetId);
        } catch (RuntimeException ignored) {
            return reference(category, targetId);
        }
    }

    public ScoreActivityTarget reference(String category, ManifestId targetId) {
        requireNonNull(targetId, "targetId must not be null");
        String type = switch (category) {
            case "code-list" -> "CODE_LIST";
            case "agency-id-list" -> "AGENCY_ID_LIST";
            case "dt-sc" -> "DT_SC";
            default -> category.toUpperCase();
        };
        return ScoreActivityTarget.primary(type, targetId, null, null);
    }

    private static ScoreActivityTarget target(
            String type,
            ManifestId manifestId,
            Guid guid,
            String name) {
        if (guid == null) {
            throw new IllegalStateException(type + " activity target guid must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(type + " activity target name must not be blank");
        }
        return ScoreActivityTarget.primary(type, manifestId, guid, name);
    }

    private static <T extends ManifestId> T requireType(
            ManifestId targetId,
            Class<T> expectedType,
            String category) {
        if (!expectedType.isInstance(targetId)) {
            throw new IllegalArgumentException(
                    "Activity target for '" + category + "' must be " + expectedType.getSimpleName());
        }
        return expectedType.cast(targetId);
    }
}
