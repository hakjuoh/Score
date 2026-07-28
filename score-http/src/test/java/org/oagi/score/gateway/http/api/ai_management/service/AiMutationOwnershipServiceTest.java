package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityKind;
import org.oagi.score.gateway.http.api.ai_management.repository.AiMutationOwnershipQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiMutationOwnershipServiceTest {

    private static final ScoreUser REQUESTER = user(11);

    private final RecordingRepository repository = new RecordingRepository();
    private final AiMutationOwnershipService ownership =
            new AiMutationOwnershipService(requester -> repository, new ObjectMapper());

    @Test
    void reportsOwnershipWhenTheTargetBelongsToTheRequester() {
        repository.owner = Optional.of(REQUESTER.userId());

        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_business_context", "{\"biz_ctx_id\":42}")).isTrue();
        assertThat(repository.lookups)
                .containsExactly(AiOwnedEntityKind.BIZ_CTX + " 42");
    }

    @Test
    void withholdsOwnershipWhenTheTargetBelongsToSomebodyElse() {
        repository.owner = Optional.of(new UserId(BigInteger.valueOf(12)));

        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_business_context", "{\"biz_ctx_id\":42}")).isFalse();
    }

    @Test
    void withholdsOwnershipWhenTheTargetIsGoneOrHasNoRecordedOwner() {
        repository.owner = Optional.empty();

        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_business_context", "{\"biz_ctx_id\":42}")).isFalse();
    }

    @Test
    void readsIdentifiersWrittenAsJsonStringsAsWellAsNumbers() {
        repository.owner = Optional.of(REQUESTER.userId());

        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_bbie", "{\"bbie_id\":\" 7 \"}")).isTrue();
        assertThat(repository.lookups).containsExactly(AiOwnedEntityKind.BBIE + " 7");
    }

    @Test
    void withholdsOwnershipWhenTheArgumentsDoNotCarryAReadableIdentifier() {
        repository.owner = Optional.of(REQUESTER.userId());

        assertThat(ownership.requesterOwnsTarget(REQUESTER, "update_bbie", "{}")).isFalse();
        assertThat(ownership.requesterOwnsTarget(REQUESTER, "update_bbie", null)).isFalse();
        assertThat(ownership.requesterOwnsTarget(REQUESTER, "update_bbie", "not json")).isFalse();
        assertThat(ownership.requesterOwnsTarget(REQUESTER, "update_bbie", "[1,2]")).isFalse();
        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_bbie", "{\"bbie_id\":\"seven\"}")).isFalse();
        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_bbie", "{\"bbie_id\":null}")).isFalse();
        assertThat(repository.lookups).isEmpty();
    }

    @Test
    void neverConsultsTheDatabaseForToolsThatAlwaysNeedApproval() {
        repository.owner = Optional.of(REQUESTER.userId());

        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "delete_business_context", "{\"biz_ctx_id\":42}")).isFalse();
        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "future_mutation", "{\"biz_ctx_id\":42}")).isFalse();
        assertThat(repository.lookups).isEmpty();
    }

    @Test
    void withholdsOwnershipWhenThereIsNoIdentifiedRequester() {
        repository.owner = Optional.of(REQUESTER.userId());

        assertThat(ownership.requesterOwnsTarget(
                null, "update_business_context", "{\"biz_ctx_id\":42}")).isFalse();
        assertThat(ownership.requesterOwnsTarget(
                user(null), "update_business_context", "{\"biz_ctx_id\":42}")).isFalse();
        assertThat(repository.lookups).isEmpty();
    }

    @Test
    void withholdsOwnershipWhenTheLookupFails() {
        repository.failure = new IllegalStateException("connection reset");

        assertThat(ownership.requesterOwnsTarget(
                REQUESTER, "update_business_context", "{\"biz_ctx_id\":42}")).isFalse();
    }

    private static ScoreUser user(Integer userId) {
        return new ScoreUser(
                userId == null ? null : new UserId(BigInteger.valueOf(userId)),
                "oagis", "OAGi Tester", "tester@example.org", true, List.of(ScoreRole.DEVELOPER));
    }

    private static final class RecordingRepository implements AiMutationOwnershipQueryRepository {

        private final List<String> lookups = new ArrayList<>();
        private Optional<UserId> owner = Optional.empty();
        private RuntimeException failure;

        @Override
        public Optional<UserId> findOwner(AiOwnedEntityKind kind, BigInteger id) {
            if (failure != null) {
                throw failure;
            }
            lookups.add(kind + " " + id);
            return owner;
        }
    }

}
