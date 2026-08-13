package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt_sc.DtScManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Provides advised boundaries for component creation invoked from another command on the same service. */
@Component
public class CoreComponentActivityExecutor {

    @ScoreActivity(category = "acc", action = "create", handler = CoreComponentActivityHandler.class)
    public AccManifestId createAcc(ScoreUser requester, Supplier<AccManifestId> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "asccp", action = "create", handler = CoreComponentActivityHandler.class)
    public AsccpManifestId createAsccp(ScoreUser requester, Supplier<AsccpManifestId> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "acc", action = "state-change", handler = CoreComponentActivityHandler.class)
    public boolean changeAccState(
            ScoreUser requester, AccManifestId targetId, CcState state, Supplier<Boolean> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "ascc", action = "create", operation = "append",
            handler = CoreComponentNestedActivityHandler.class)
    public AsccManifestId createAscc(ScoreUser requester, Supplier<AsccManifestId> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "bcc", action = "create", operation = "append",
            handler = CoreComponentNestedActivityHandler.class)
    public BccManifestId createBcc(ScoreUser requester, Supplier<BccManifestId> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "dt-sc", action = "create", operation = "append",
            handler = CoreComponentNestedActivityHandler.class)
    public DtScManifestId createDtSc(ScoreUser requester, Supplier<DtScManifestId> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "ascc", action = "update", operation = "modify",
            handler = CoreComponentNestedActivityHandler.class)
    public <T> T updateAscc(
            ScoreUser requester, AsccManifestId targetId, Supplier<T> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "bcc", action = "update", operation = "modify",
            handler = CoreComponentNestedActivityHandler.class)
    public <T> T updateBcc(
            ScoreUser requester, BccManifestId targetId, Supplier<T> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "dt-sc", action = "update", operation = "modify",
            handler = CoreComponentNestedActivityHandler.class)
    public <T> T updateDtSc(
            ScoreUser requester, DtScManifestId targetId, Supplier<T> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "ascc", action = "delete", operation = "discard",
            handler = CoreComponentNestedActivityHandler.class)
    public boolean deleteAscc(ScoreUser requester, AsccManifestId targetId, Supplier<Boolean> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "bcc", action = "delete", operation = "discard",
            handler = CoreComponentNestedActivityHandler.class)
    public boolean deleteBcc(ScoreUser requester, BccManifestId targetId, Supplier<Boolean> operation) {
        return execute(operation);
    }

    @ScoreActivity(category = "dt-sc", action = "delete", operation = "discard",
            handler = CoreComponentNestedActivityHandler.class)
    public boolean deleteDtSc(ScoreUser requester, DtScManifestId targetId, Supplier<Boolean> operation) {
        return execute(operation);
    }

    private static <T> T execute(Supplier<T> operation) {
        return requireNonNull(operation, "operation must not be null").get();
    }
}
