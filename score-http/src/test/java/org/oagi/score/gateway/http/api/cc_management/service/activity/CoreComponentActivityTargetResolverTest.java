package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.repository.AccQueryRepository;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.math.BigInteger;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreComponentActivityTargetResolverTest {

    private final RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
    private final AccQueryRepository accQueryRepository = mock(AccQueryRepository.class);
    private final CoreComponentActivityTargetResolver resolver =
            new CoreComponentActivityTargetResolver(repositoryFactory);
    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.ONE), "oagis", "OAGIS", null, false,
            List.of(ScoreRole.DEVELOPER));

    @Test
    void resolvesAccManifestIdentityWithGuidAndObjectClassTerm() {
        AccManifestId manifestId = new AccManifestId(BigInteger.valueOf(100005480));
        Guid guid = new Guid("0123456789abcdef0123456789abcdef");
        AccSummaryRecord summary = mock(AccSummaryRecord.class);
        when(summary.guid()).thenReturn(guid);
        when(summary.objectClassTerm()).thenReturn("Invoice");
        when(repositoryFactory.accQueryRepository(requester)).thenReturn(accQueryRepository);
        when(accQueryRepository.getAccSummary(manifestId)).thenReturn(summary);

        var target = resolver.resolve("acc", requester, manifestId);

        assertThat(target.type()).isEqualTo("ACC");
        assertThat(target.id()).isEqualTo("100005480");
        assertThat(target.guid()).isEqualTo(guid.value());
        assertThat(target.name()).isEqualTo("Invoice");
        assertThat(target.role()).isEqualTo("PRIMARY");
    }

    @Test
    void enrichesTheAccCreateEventUsedByTheActivityHandler() {
        AccManifestId manifestId = new AccManifestId(BigInteger.valueOf(100005480));
        Guid guid = new Guid("0123456789abcdef0123456789abcdef");
        AccSummaryRecord summary = mock(AccSummaryRecord.class);
        when(summary.guid()).thenReturn(guid);
        when(summary.objectClassTerm()).thenReturn("Invoice");
        when(repositoryFactory.accQueryRepository(requester)).thenReturn(accQueryRepository);
        when(accQueryRepository.getAccSummary(manifestId)).thenReturn(summary);
        var events = new CoreComponentActivityEventFactory(
                new ScoreActivityEventFactory(Clock.systemUTC(), ScoreActivityContext::empty),
                resolver);

        var event = events.created("acc", requester, manifestId);

        assertThat(event.targets()).singleElement().satisfies(target -> {
            assertThat(target.id()).isEqualTo("100005480");
            assertThat(target.guid()).isEqualTo(guid.value());
            assertThat(target.name()).isEqualTo("Invoice");
        });
    }

    @Test
    void rejectsAnIncompleteSuccessfulAccIdentity() {
        AccManifestId manifestId = new AccManifestId(BigInteger.valueOf(100005480));
        AccSummaryRecord summary = mock(AccSummaryRecord.class);
        when(summary.guid()).thenReturn(null);
        when(summary.objectClassTerm()).thenReturn("Invoice");
        when(repositoryFactory.accQueryRepository(requester)).thenReturn(accQueryRepository);
        when(accQueryRepository.getAccSummary(manifestId)).thenReturn(summary);

        assertThatThrownBy(() -> resolver.resolve("acc", requester, manifestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ACC activity target guid must not be null");
    }
}
