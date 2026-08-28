package org.oagi.score.gateway.http.api.bie_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListId;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListManifestId;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.service.CcMatchingService;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListId;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListManifestId;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListSummaryRecord;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtId;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtManifestId;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtSummaryRecord;
import org.oagi.score.gateway.http.common.model.Guid;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

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

    @Test
    void resolvesTargetCodeListByGuidBeforeFallbackIdentifiers() {
        CodeListSummaryRecord source = codeList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Source", "LIST", "1");
        CodeListSummaryRecord target = codeList(202, 8, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Different name", "OTHER", "2");

        CodeListSummaryRecord result = new BieUpliftingService()
                .getTargetCodeListManifest(source, List.of(target));

        assertThat(result).isSameAs(target);
    }

    @Test
    void resolvesUpliftedCodeListByNameListIdAndVersion() {
        CodeListSummaryRecord source = codeList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Test Code List", "TEST", "1");
        CodeListSummaryRecord target = codeList(202, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Test Code List", "TEST", "1");

        CodeListSummaryRecord result = new BieUpliftingService()
                .getTargetCodeListManifest(source, List.of(target));

        assertThat(result).isSameAs(target);
    }

    @Test
    void resolvesUpliftedAgencyIdListByItsIdentifiers() {
        AgencyIdListSummaryRecord source = agencyIdList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Test Agency List", "TEST", "1");
        AgencyIdListSummaryRecord target = agencyIdList(202, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Test Agency List", "TEST", "1");

        AgencyIdListSummaryRecord result = new BieUpliftingService()
                .getTargetAgencyIdListManifest(source, List.of(target));

        assertThat(result).isSameAs(target);
    }

    @Test
    void doesNotSelectMatchingCodeListWhenTargetNodeDoesNotAllowIt() {
        CodeListSummaryRecord source = codeList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Test Code List", "TEST", "1");
        CodeListSummaryRecord target = codeList(202, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Test Code List", "TEST", "1");

        CodeListSummaryRecord result = new BieUpliftingService()
                .getTargetCodeListManifest(source, List.of(target), Set.of(new CodeListManifestId(BigInteger.valueOf(999))));

        assertThat(result).isNull();
    }

    @Test
    void selectsMatchingAgencyIdListWhenTargetNodeAllowsIt() {
        AgencyIdListSummaryRecord source = agencyIdList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Test Agency List", "TEST", "1");
        AgencyIdListSummaryRecord target = agencyIdList(202, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Test Agency List", "TEST", "1");

        AgencyIdListSummaryRecord result = new BieUpliftingService()
                .getTargetAgencyIdListManifest(source, List.of(target), Set.of(target.agencyIdListManifestId()));

        assertThat(result).isSameAs(target);
    }

    @Test
    void selectsTheAllowedTargetManifestForAGuidMatchedCodeList() {
        CodeListSummaryRecord source = codeList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Source", "SOURCE", "1");
        CodeListSummaryRecord guidMatched = codeList(202, 8, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Target", "TARGET", "2");
        CodeListSummaryRecord allowedManifest = codeList(203, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Target", "TARGET", "2");

        CodeListSummaryRecord result = new BieUpliftingService()
                .getTargetCodeListManifest(source, List.of(guidMatched, allowedManifest),
                        Set.of(allowedManifest.codeListManifestId()));

        assertThat(result).isSameAs(allowedManifest);
    }

    @Test
    void selectsTheAllowedTargetManifestForAGuidMatchedAgencyIdList() {
        AgencyIdListSummaryRecord source = agencyIdList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Source", "SOURCE", "1");
        AgencyIdListSummaryRecord guidMatched = agencyIdList(202, 8, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Target", "TARGET", "2");
        AgencyIdListSummaryRecord allowedManifest = agencyIdList(203, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Target", "TARGET", "2");

        AgencyIdListSummaryRecord result = new BieUpliftingService()
                .getTargetAgencyIdListManifest(source, List.of(guidMatched, allowedManifest),
                        Set.of(allowedManifest.agencyIdListManifestId()));

        assertThat(result).isSameAs(allowedManifest);
    }

    @Test
    void doesNotTreatNullGuidsAsAnExactMatch() {
        CodeListSummaryRecord source = codeList(101, 7, null, "Source", "SOURCE", "1");
        CodeListSummaryRecord target = codeList(202, 8, null, "Target", "TARGET", "1");

        CodeListSummaryRecord result = new BieUpliftingService()
                .getTargetCodeListManifest(source, List.of(target));

        assertThat(result).isNull();
    }

    @Test
    void usesCcMatchingServiceForCodeAndAgencyIdentityRecords() {
        CcMatchingService matchingService = new CcMatchingService();
        CodeListSummaryRecord sourceCodeList = codeList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Code", "CODE", "1");
        CodeListSummaryRecord targetCodeList = codeList(202, 8, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Code", "CODE", "1");
        AgencyIdListSummaryRecord sourceAgencyIdList = agencyIdList(301, 9, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Agency", "AGENCY", "1");
        AgencyIdListSummaryRecord targetAgencyIdList = agencyIdList(302, 10, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Agency", "AGENCY", "1");

        assertThat(matchingService.score(sourceCodeList, targetCodeList)).isEqualTo(1.0d);
        assertThat(matchingService.score(sourceAgencyIdList, targetAgencyIdList)).isEqualTo(1.0d);
    }

    @Test
    void doesNotSelectAnUnrelatedTargetWhenSharedCodeListIdIsNull() {
        CodeListSummaryRecord source = codeList(101, null, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Source", "SOURCE", "1");
        CodeListSummaryRecord target = codeList(202, null, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Target", "TARGET", "1");

        assertThat(new BieUpliftingService().getTargetCodeListManifest(source, List.of(target))).isNull();
    }

    @Test
    void doesNotResolveAgencyIdListWhenItsValueNameDiffers() {
        AgencyIdListSummaryRecord source = agencyIdList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Agency", "AGENCY", "1", "Source value");
        AgencyIdListSummaryRecord target = agencyIdList(202, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Agency", "AGENCY", "1", "Target value");

        assertThat(new BieUpliftingService().getTargetAgencyIdListManifest(source, List.of(target))).isNull();
    }

    @Test
    void resolvesAgencyIdListWhenAllFallbackIdentifiersIncludingValueNameMatch() {
        AgencyIdListSummaryRecord source = agencyIdList(101, 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Agency", "AGENCY", "1", "Value");
        AgencyIdListSummaryRecord target = agencyIdList(202, 8, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Agency", "AGENCY", "1", "Value");

        assertThat(new BieUpliftingService().getTargetAgencyIdListManifest(source, List.of(target))).isSameAs(target);
    }

    @Test
    void doesNotSelectAnUnrelatedTargetWhenSharedAgencyIdListIdIsNull() {
        AgencyIdListSummaryRecord source = agencyIdList(101, null, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Source", "SOURCE", "1");
        AgencyIdListSummaryRecord target = agencyIdList(202, null, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Target", "TARGET", "1");

        assertThat(new BieUpliftingService().getTargetAgencyIdListManifest(source, List.of(target))).isNull();
    }

    private static CodeListSummaryRecord codeList(int manifestId, Integer codeListId, String guid,
                                                   String name, String listId, String versionId) {
        return new CodeListSummaryRecord(
                new CodeListManifestId(BigInteger.valueOf(manifestId)),
                codeListId != null ? new CodeListId(BigInteger.valueOf(codeListId)) : null,
                guid != null ? new Guid(guid) : null, null, null, null,
                name, listId, versionId, null, null, false,
                CcState.Published, null, null, null, null);
    }

    private static AgencyIdListSummaryRecord agencyIdList(int manifestId, int agencyIdListId, String guid,
                                                           String name, String listId, String versionId) {
        return agencyIdList(manifestId, agencyIdListId, guid, name, listId, versionId, null);
    }

    private static AgencyIdListSummaryRecord agencyIdList(int manifestId, Integer agencyIdListId, String guid,
                                                           String name, String listId, String versionId) {
        return agencyIdList(manifestId, agencyIdListId, guid, name, listId, versionId, null);
    }

    private static AgencyIdListSummaryRecord agencyIdList(int manifestId, Integer agencyIdListId, String guid,
                                                           String name, String listId, String versionId,
                                                           String valueName) {
        return new AgencyIdListSummaryRecord(
                new AgencyIdListManifestId(BigInteger.valueOf(manifestId)),
                agencyIdListId != null ? new AgencyIdListId(BigInteger.valueOf(agencyIdListId)) : null,
                new Guid(guid), null,
                name, listId, versionId, null, null, null, valueName,
                false, CcState.Published, null, null, null, null);
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
