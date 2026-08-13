package org.oagi.score.gateway.http.api.cc_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.ascc.AsccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.ascc.AsccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bcc.BccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bcc.BccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.dt.DtUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.dt_sc.DtScUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityExecutor;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CcCommandServiceActivityValidationTest {

    private static final ScoreUser REQUESTER = new ScoreUser(
            new UserId(BigInteger.ONE), "developer", "Developer", null, false,
            List.of(ScoreRole.DEVELOPER));

    @Test
    void batchUpdatesRejectNullManifestIdsAsValidationFailures() {
        CcCommandService service = new CcCommandService();

        assertValidation(() -> service.updateAsccList(REQUESTER,
                List.of(new AsccUpdateRequest(null, null, null, null, null, null))));
        assertValidation(() -> service.updateBccList(REQUESTER,
                List.of(new BccUpdateRequest(
                        null, null, null, null, null, null, null, null, null, null))));
        assertValidation(() -> service.updateDtList(REQUESTER,
                List.of(DtUpdateRequest.builder(null).build())));
        assertValidation(() -> service.updateDtScList(REQUESTER,
                List.of(DtScUpdateRequest.builder(null).build())));
    }

    @Test
    void associationCreatesRejectNullParentOrPropertyManifestIds() {
        CcCommandService service = new CcCommandService();
        ReflectionTestUtils.setField(service, "coreComponentActivityExecutor",
                new CoreComponentActivityExecutor());
        AsccpManifestId asccpId = new AsccpManifestId(BigInteger.valueOf(2));
        BccpManifestId bccpId = new BccpManifestId(BigInteger.valueOf(3));
        AccManifestId accId = new AccManifestId(BigInteger.valueOf(4));

        assertValidation(() -> service.createAscc(
                REQUESTER, AsccCreateRequest.builder(null, asccpId).build()));
        assertValidation(() -> service.createAscc(
                REQUESTER, AsccCreateRequest.builder(accId, null).build()));
        assertValidation(() -> service.createBcc(
                REQUESTER, BccCreateRequest.builder(null, bccpId).build()));
        assertValidation(() -> service.createBcc(
                REQUESTER, BccCreateRequest.builder(accId, null).build()));
    }

    private static void assertValidation(Runnable operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isInstanceOfSatisfying(ScoreActivityException.class,
                exception -> assertThat(exception.failureCode())
                        .isEqualTo(ScoreActivityFailureCode.VALIDATION_ERROR));
    }
}
