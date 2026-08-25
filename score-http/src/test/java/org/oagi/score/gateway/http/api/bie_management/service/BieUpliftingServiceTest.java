package org.oagi.score.gateway.http.api.bie_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtId;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtManifestId;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtSummaryRecord;
import org.oagi.score.gateway.http.common.model.Guid;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BieUpliftingServiceTest {

    @Test
    void resolvesTargetPrimitiveBySharedXbtIdAcrossReleaseManifests() {
        XbtSummaryRecord sourceXbt = xbt(101, 7, 'a');
        XbtSummaryRecord targetXbt = xbt(202, 7, 'b');

        XbtSummaryRecord result = new BieUpliftingService()
                .getTargetXbtManifest(sourceXbt, List.of(targetXbt));

        assertThat(result).isSameAs(targetXbt);
    }

    @Test
    void doesNotResolveTargetPrimitiveWhenXbtIdDiffersEvenIfGuidMatches() {
        XbtSummaryRecord sourceXbt = xbt(101, 7, 'a');
        XbtSummaryRecord targetXbt = xbt(202, 8, 'a');

        XbtSummaryRecord result = new BieUpliftingService()
                .getTargetXbtManifest(sourceXbt, List.of(targetXbt));

        assertThat(result).isNull();
    }

    private static XbtSummaryRecord xbt(int manifestId, int xbtId, char guidCharacter) {
        return new XbtSummaryRecord(
                new XbtManifestId(BigInteger.valueOf(manifestId)),
                new XbtId(BigInteger.valueOf(xbtId)),
                null,
                new Guid(String.valueOf(guidCharacter).repeat(32)),
                "primitive-" + xbtId,
                "xsd:string",
                null, null, null, null, null, null);
    }
}
