package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListManifestId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.acc.AccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.AsccpUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bccp.BccpUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListManifestId;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoreComponentActivityHandlerTest {

    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.valueOf(7)),
            "developer", "Developer", null, false, List.of(ScoreRole.DEVELOPER));
    private final CoreComponentActivityHandler handler = new CoreComponentActivityHandler(
            CoreComponentActivityTestSupport.eventFactory(),
            new ScoreActivityFailureClassifier());

    @Test
    void supportsEveryLifecycleActionForAllConfiguredComponentTypes() {
        for (String category : List.of("acc", "asccp", "bccp")) {
            for (String action : List.of("create", "update", "state-change", "delete")) {
                assertThat(handler.supports(invocation(category, action, requester, null)))
                        .as(category + "." + action)
                        .isTrue();
            }
        }
        assertThat(handler.supports(invocation("dt", "create", requester, null))).isFalse();
        for (String category : List.of("dt", "code-list", "agency-id-list")) {
            assertThat(handler.supports(invocation(category, "state-change", requester, null))).isTrue();
            assertThat(handler.supports(invocation(category, "update", requester, null))).isFalse();
        }
    }

    @Test
    void releaseManagedComponentTypesUseTheSameStateChangeShape() {
        List<ComponentCase> components = List.of(
                new ComponentCase("dt", "DT", new DtManifestId(BigInteger.valueOf(44))),
                new ComponentCase("code-list", "CODE_LIST", new CodeListManifestId(BigInteger.valueOf(45))),
                new ComponentCase("agency-id-list", "AGENCY_ID_LIST",
                        new AgencyIdListManifestId(BigInteger.valueOf(46))));

        for (ComponentCase component : components) {
            ScoreActivityEvent event = handler.start(invocation(
                            component.category(), "state-change",
                            requester, component.id(), CcState.ReleaseDraft))
                    .succeeded(true).getFirst();

            assertThat(event.name()).isEqualTo(component.category() + ".state-change");
            assertThat(event.targets()).singleElement().satisfies(target -> {
                assertThat(target.type()).isEqualTo(component.targetType());
                assertThat(target.id()).isEqualTo(component.id().toString());
            });
            assertThat(event.properties()).containsEntry("toState", "ReleaseDraft");
        }
    }

    @Test
    void lifecycleActionsUseTheCategorySpecificTargetWithoutSynchronousLookup() {
        for (ComponentCase component : components()) {
            ScoreActivityEvent create = handler.start(invocation(
                            component.category(), "create", requester, null))
                    .succeeded(component.id()).getFirst();
            ScoreActivityEvent state = handler.start(invocation(
                            component.category(), "state-change",
                            requester, component.id(), CcState.Deleted))
                    .succeeded(true).getFirst();
            ScoreActivityEvent delete = handler.start(invocation(
                            component.category(), "delete", requester, component.id()))
                    .succeeded(true).getFirst();

            assertThat(List.of(create, state, delete)).allSatisfy(event -> {
                assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.SUCCEEDED);
                assertThat(event.targets().getFirst().type()).isEqualTo(component.targetType());
                assertThat(event.targets().getFirst().id()).isEqualTo(component.id().toString());
            });
            assertThat(state.properties()).containsEntry("toState", "Deleted");
        }
    }

    @Test
    void standardUpdatesExposeOnlyTheFieldsRequestedByEachComponentContract() {
        AccUpdateRequest acc = new AccUpdateRequest(
                new AccManifestId(BigInteger.valueOf(41)), null, "Order", null,
                null, null, null, null, null);
        AsccpUpdateRequest asccp = new AsccpUpdateRequest(
                new AsccpManifestId(BigInteger.valueOf(42)), "Order", null, null,
                true, null, null, null);
        BccpUpdateRequest bccp = new BccpUpdateRequest(
                new BccpManifestId(BigInteger.valueOf(43)), "Identifier", null, null,
                null, null, null, null, null);

        assertUpdatedFields("acc", acc, List.of("objectClassTerm", "namespaceId"));
        assertUpdatedFields("asccp", asccp, List.of("propertyTerm", "reusable", "namespaceId"));
        assertUpdatedFields("bccp", bccp, List.of("propertyTerm", "namespaceId"));
    }

    @Test
    void directReferenceUpdatesUseTheSameUpdateEventShape() {
        AsccpManifestId asccpId = new AsccpManifestId(BigInteger.valueOf(42));
        BccpManifestId bccpId = new BccpManifestId(BigInteger.valueOf(43));

        ScoreActivityEvent asccp = handler.start(invocation(
                        "asccp", "update", requester, asccpId,
                        new AccManifestId(BigInteger.ONE)))
                .succeeded(true).getFirst();
        ScoreActivityEvent bccp = handler.start(invocation(
                        "bccp", "update", requester, bccpId,
                        new AccManifestId(BigInteger.ONE)))
                .succeeded(true).getFirst();

        assertThat(asccp.properties()).containsEntry(
                "requestedFields", List.of("roleOfAccManifestId"));
        assertThat(bccp.properties()).containsEntry(
                "requestedFields", List.of("dtManifestId"));
    }

    @Test
    void batchProducesOneEventPerRequestedComponentAndUsesRollbackCodeForAll() {
        AsccpUpdateRequest first = new AsccpUpdateRequest(
                new AsccpManifestId(BigInteger.valueOf(41)), "Order", null, null,
                null, null, null, null);
        AsccpUpdateRequest second = new AsccpUpdateRequest(
                new AsccpManifestId(BigInteger.valueOf(42)), "Invoice", null, null,
                null, null, null, null);
        var execution = handler.start(invocation(
                "asccp", "update", requester, List.of(first, second)));

        assertThat(execution.succeeded(List.of(first.asccpManifestId())))
                .extracting(ScoreActivityEvent::outcome)
                .containsExactly(ScoreActivityEvent.SUCCEEDED, ScoreActivityEvent.FAILED);
        assertThat(execution.isSuccessful(List.of(first.asccpManifestId()))).isFalse();
        assertThat(execution.isSuccessful(List.of(
                first.asccpManifestId(), second.asccpManifestId()))).isTrue();
        assertThat(execution.failed(ScoreActivityException.invalidState("private")))
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.properties())
                        .containsEntry("errorCode", "BATCH_ROLLED_BACK")
                        .doesNotContainValue("private"));
    }

    @Test
    void resultClassificationMatchesFailureEventsWhenNoMutationWasApplied() {
        var create = handler.start(invocation("acc", "create", requester, null));
        var update = handler.start(invocation(
                "acc", "update", requester,
                new AccUpdateRequest(new AccManifestId(BigInteger.valueOf(41)), null, "Order", null,
                        null, null, null, null, null)));

        assertThat(create.isSuccessful(null)).isFalse();
        assertThat(create.succeeded(null).getFirst().outcome()).isEqualTo(ScoreActivityEvent.FAILED);
        assertThat(update.isSuccessful(false)).isFalse();
        assertThat(update.succeeded(false).getFirst().outcome()).isEqualTo(ScoreActivityEvent.FAILED);
    }

    @Test
    void failureEventsKeepStableCodesAndNeverExposeExceptionMessages() {
        for (ComponentCase component : components()) {
            ScoreActivityEvent event = handler.start(invocation(
                            component.category(), "delete", requester, component.id()))
                    .failed(new RuntimeException("database password"))
                    .getFirst();

            assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.FAILED);
            assertThat(event.properties())
                    .containsEntry("errorCode", "INTERNAL_ERROR")
                    .doesNotContainValue("database password");
        }
    }

    private void assertUpdatedFields(String category, Object request, List<String> expectedFields) {
        ScoreActivityEvent event = handler.start(invocation(category, "update", requester, request))
                .succeeded(true).getFirst();
        assertThat(event.properties()).containsEntry("requestedFields", expectedFields);
    }

    private static List<ComponentCase> components() {
        return List.of(
                new ComponentCase("acc", "ACC", new AccManifestId(BigInteger.valueOf(41))),
                new ComponentCase("asccp", "ASCCP", new AsccpManifestId(BigInteger.valueOf(42))),
                new ComponentCase("bccp", "BCCP", new BccpManifestId(BigInteger.valueOf(43))));
    }

    private static ScoreActivityInvocation invocation(
            String category, String action, Object... arguments) {
        return new ScoreActivityInvocation(category, action, Arrays.asList(arguments));
    }

    private record ComponentCase(String category, String targetType, ManifestId id) {
    }
}
