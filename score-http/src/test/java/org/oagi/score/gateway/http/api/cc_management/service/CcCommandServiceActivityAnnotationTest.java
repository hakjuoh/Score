package org.oagi.score.gateway.http.api.cc_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivityHandlerBinding;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.acc.AccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.acc.AccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.ascc.AsccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.AsccpCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.CreateOagisBodRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.CreateOagisVerbRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bcc.BccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bccp.BccpCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.dt.DtCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.AsccpOrBccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt_sc.DtScManifestId;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityExecutor;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentNestedActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentOperationActivityHandler;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CcCommandServiceActivityAnnotationTest {

    @Test
    void accAsccpAndBccpEntryPointsUseOneHandlerContract() throws Exception {
        ScoreActivityHandlerBinding binding = CcCommandService.class
                .getAnnotation(ScoreActivityHandlerBinding.class);
        assertThat(binding).isNotNull();
        assertThat(binding.value()).isEqualTo(CoreComponentActivityHandler.class);

        assertActivity("createAcc", "acc.create", ScoreUser.class, AccCreateRequest.class);
        assertActivity("updateAcc", "acc.update", ScoreUser.class, AccUpdateRequest.class);
        assertActivity("updateAccList", "acc.update", ScoreUser.class, List.class);
        assertStateActivities("acc", AccManifestId.class);
        assertActivity("purge", "acc.delete", ScoreUser.class, AccManifestId.class);

        assertActivity("createAsccp", "asccp.create", ScoreUser.class, AsccpCreateRequest.class);
        assertActivity("updateAsccpList", "asccp.update", ScoreUser.class, List.class);
        assertActivity("updateAsccpRoleOfAcc", "asccp.update",
                ScoreUser.class, AsccpManifestId.class, AccManifestId.class);
        assertStateActivities("asccp", AsccpManifestId.class);
        assertActivity("purge", "asccp.delete", ScoreUser.class, AsccpManifestId.class);
        assertActivity("purge", "asccp.delete",
                ScoreUser.class, AsccpManifestId.class, boolean.class);

        assertActivity("createBccp", "bccp.create", ScoreUser.class, BccpCreateRequest.class);
        assertActivity("updateBccpList", "bccp.update", ScoreUser.class, List.class);
        assertActivity("updateBccpDt", "bccp.update",
                ScoreUser.class, BccpManifestId.class, DtManifestId.class);
        assertStateActivities("bccp", BccpManifestId.class);
        assertActivity("purge", "bccp.delete", ScoreUser.class, BccpManifestId.class);
    }

    @Test
    void oneExecutorProvidesEveryNestedCreateBoundary() throws Exception {
        assertExecutorActivity("createAcc", "acc.create");
        assertExecutorActivity("createAsccp", "asccp.create");
        ScoreActivity stateActivity = CoreComponentActivityExecutor.class.getMethod(
                        "changeAccState", ScoreUser.class, AccManifestId.class, CcState.class, Supplier.class)
                .getAnnotation(ScoreActivity.class);
        assertThat(stateActivity.category() + "." + stateActivity.action()).isEqualTo("acc.state-change");
        assertThat(stateActivity.handler()).isEqualTo(CoreComponentActivityHandler.class);
        assertNestedExecutorActivity("createAscc", "ascc.create", ScoreUser.class, Supplier.class);
        assertNestedExecutorActivity("createBcc", "bcc.create", ScoreUser.class, Supplier.class);
        assertNestedExecutorActivity("createDtSc", "dt-sc.create", ScoreUser.class, Supplier.class);
        assertNestedExecutorActivity("updateAscc", "ascc.update",
                ScoreUser.class, AsccManifestId.class, Supplier.class);
        assertNestedExecutorActivity("updateBcc", "bcc.update",
                ScoreUser.class, BccManifestId.class, Supplier.class);
        assertNestedExecutorActivity("updateDtSc", "dt-sc.update",
                ScoreUser.class, DtScManifestId.class, Supplier.class);
        assertNestedExecutorActivity("deleteAscc", "ascc.delete",
                ScoreUser.class, AsccManifestId.class, Supplier.class);
        assertNestedExecutorActivity("deleteBcc", "bcc.delete",
                ScoreUser.class, BccManifestId.class, Supplier.class);
        assertNestedExecutorActivity("deleteDtSc", "dt-sc.delete",
                ScoreUser.class, DtScManifestId.class, Supplier.class);
    }

    @Test
    void everyUiCoreComponentMutationHasAnActivityBoundary() throws Exception {
        assertOperation("createAscc", "acc.update", "append-ascc",
                ScoreUser.class, AsccCreateRequest.class);
        assertOperation("createBcc", "acc.update", "append-bcc",
                ScoreUser.class, BccCreateRequest.class);
        assertOperation("createOagisBod", "oagis-bod.create", "generate-bod",
                ScoreUser.class, CreateOagisBodRequest.class);
        assertOperation("createOagisVerb", "oagis-verb.create", "generate-verb",
                ScoreUser.class, CreateOagisVerbRequest.class);
        assertOperation("createDt", "dt.create", "create-dt",
                ScoreUser.class, DtCreateRequest.class);
        assertOperation("createAccExtension", "acc.update", "create-extension",
                ScoreUser.class, AccManifestId.class);
        assertOperation("updateAsccList", "acc.update", "update-ascc", ScoreUser.class, List.class);
        assertOperation("updateBccList", "acc.update", "update-bcc", ScoreUser.class, List.class);
        assertOperation("updateDtList", "dt.update", "update-details", ScoreUser.class, List.class);
        assertOperation("updateDtScList", "dt.update", "update-dt-sc", ScoreUser.class, List.class);
        assertOperation("updateBasedAccManifestId", "acc.update", "update-base",
                ScoreUser.class, AccManifestId.class, AccManifestId.class);
        assertOperation("updateAccSequence", "acc.update", "update-sequence",
                ScoreUser.class, AccManifestId.class, AsccpOrBccpManifestId.class,
                AsccpOrBccpManifestId.class);
        assertOperation("discard", "acc.update", "discard-ascc",
                ScoreUser.class, AsccManifestId.class);
        assertOperation("discard", "acc.update", "discard-bcc",
                ScoreUser.class, BccManifestId.class);
        assertOperation("discard", "dt.update", "discard-dt-sc",
                ScoreUser.class, DtScManifestId.class, boolean.class);
        assertOperation("createDtSc", "dt.update", "append-dt-sc",
                ScoreUser.class, DtManifestId.class);
        assertOperation("purge", "dt.delete", "purge", ScoreUser.class, DtManifestId.class);
        assertOperation("refactorAscc", "acc.update", "refactor-ascc",
                ScoreUser.class, AsccManifestId.class, AccManifestId.class);
        assertOperation("refactorBcc", "acc.update", "refactor-bcc",
                ScoreUser.class, BccManifestId.class, AccManifestId.class);
        assertOperation("ungroup", "acc.update", "ungroup",
                ScoreUser.class, AccManifestId.class, AsccManifestId.class, int.class);

        assertRevisionOperations("acc", AccManifestId.class, "reviseAcc", "cancelAcc");
        assertRevisionOperations("asccp", AsccpManifestId.class, "reviseAsccp", "cancelAsccp");
        assertRevisionOperations("bccp", BccpManifestId.class, "reviseBccp", "cancelBccp");
        assertRevisionOperations("dt", DtManifestId.class, "reviseDt", "cancelDt");
        assertTransferOperation("acc", AccManifestId.class);
        assertTransferOperation("asccp", AsccpManifestId.class);
        assertTransferOperation("bccp", BccpManifestId.class);
        assertTransferOperation("dt", DtManifestId.class);
    }

    private static void assertStateActivities(String category, Class<?> idType) throws Exception {
        assertActivity("updateState", category + ".state-change",
                ScoreUser.class, idType, CcState.class);
        assertActivity("updateState", category + ".state-change",
                ScoreUser.class, idType, CcState.class, String.class);
        assertActivity("updateState", category + ".state-change",
                ScoreUser.class, idType, CcState.class, String.class, String.class);
    }

    private static void assertExecutorActivity(String method, String expectedName) throws Exception {
        ScoreActivity activity = CoreComponentActivityExecutor.class
                .getMethod(method, ScoreUser.class, Supplier.class)
                .getAnnotation(ScoreActivity.class);
        assertThat(activity).isNotNull();
        assertThat(activity.category() + "." + activity.action()).isEqualTo(expectedName);
        assertThat(activity.handler()).isEqualTo(CoreComponentActivityHandler.class);
    }

    private static void assertNestedExecutorActivity(
            String method, String expectedName, Class<?>... parameterTypes) throws Exception {
        ScoreActivity activity = CoreComponentActivityExecutor.class
                .getMethod(method, parameterTypes)
                .getAnnotation(ScoreActivity.class);
        assertThat(activity).isNotNull();
        assertThat(activity.category() + "." + activity.action()).isEqualTo(expectedName);
        assertThat(activity.operation()).isNotBlank();
        assertThat(activity.handler()).isEqualTo(CoreComponentNestedActivityHandler.class);
    }

    private static void assertRevisionOperations(
            String category, Class<?> idType, String reviseMethod, String cancelMethod) throws Exception {
        assertOperation(reviseMethod, category + ".state-change", "revise", ScoreUser.class, idType);
        assertOperation(cancelMethod, category + ".state-change", "cancel",
                ScoreUser.class, idType, String.class, String.class);
    }

    private static void assertTransferOperation(String category, Class<?> idType) throws Exception {
        assertOperation("transferOwnership", category + ".update", "transfer-ownership",
                ScoreUser.class, ScoreUser.class, idType);
    }

    private static void assertOperation(
            String methodName, String expectedName, String operation,
            Class<?>... parameterTypes) throws Exception {
        ScoreActivity activity = CcCommandService.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(ScoreActivity.class);
        assertThat(activity).as(methodName + " activity annotation").isNotNull();
        assertThat(activity.category() + "." + activity.action()).isEqualTo(expectedName);
        assertThat(activity.operation()).isEqualTo(operation);
        assertThat(activity.handler()).isEqualTo(CoreComponentOperationActivityHandler.class);
    }

    private static void assertActivity(
            String methodName,
            String expectedName,
            Class<?>... parameterTypes) throws Exception {
        ScoreActivity activity = CcCommandService.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(ScoreActivity.class);

        assertThat(activity).as(methodName + " activity annotation").isNotNull();
        assertThat(activity.category() + "." + activity.action()).isEqualTo(expectedName);
        assertThat(activity.handler()).isEqualTo(ScoreActivityHandler.class);
    }
}
