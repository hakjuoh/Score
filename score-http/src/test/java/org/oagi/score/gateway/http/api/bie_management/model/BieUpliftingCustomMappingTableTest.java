package org.oagi.score.gateway.http.api.bie_management.model;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.cc_management.model.CcDocument;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BieUpliftingCustomMappingTableTest {

    @Test
    void convertsVisitorPathsToTheLegacyReuseLookupShape() {
        assertThat(BieUpliftingCustomMappingTable.legacySourcePath(
                "ASCCP-1>ACC-2>ASCC-3>ASCCP-4>ACC-5>ACC-6>BCC-7"))
                .isEqualTo("ASCCP-4>ACC-6>BCC-7");
    }

    @Test
    void leavesPathsWithoutAnOwnerAsccpUnchanged() {
        assertThat(BieUpliftingCustomMappingTable.legacySourcePath("ASCC-3>BCC-7"))
                .isEqualTo("ASCC-3>BCC-7");
    }

    @Test
    void resolvesLegacyPrimitiveMappingPathsAgainstVisitorPaths() {
        BieUpliftingMapping mapping = new BieUpliftingMapping();
        mapping.setSourcePath("ASCCP-4>ACC-6>BCC-7>DT-8>DT_SC-9");
        mapping.setTargetPath("TARGET>DT_SC-19");

        BieUpliftingCustomMappingTable table = new BieUpliftingCustomMappingTable(
                null, null, List.of(mapping));

        assertThat(table.getTargetDtScMappingBySourcePath(
                "ASCCP-1>ACC-2>ASCCP-4>ACC-5>ACC-6>BCC-7>DT-8>DT_SC-9"))
                .isSameAs(mapping);
    }

    @Test
    void resolvesLegacyPathsForAllManualMappingKinds() {
        CcDocument sourceCcDocument = mock(CcDocument.class);
        CcDocument targetCcDocument = mock(CcDocument.class);
        AsccSummaryRecord sourceAscc = mock(AsccSummaryRecord.class);
        AsccSummaryRecord targetAscc = mock(AsccSummaryRecord.class);
        AsccpSummaryRecord sourceAsccp = mock(AsccpSummaryRecord.class);
        AsccpSummaryRecord targetAsccp = mock(AsccpSummaryRecord.class);
        BccSummaryRecord sourceBcc = mock(BccSummaryRecord.class);
        BccSummaryRecord targetBcc = mock(BccSummaryRecord.class);

        when(sourceCcDocument.getAscc(any(AsccManifestId.class))).thenReturn(sourceAscc);
        when(targetCcDocument.getAscc(any(AsccManifestId.class))).thenReturn(targetAscc);
        when(sourceAscc.toAsccpManifestId()).thenReturn(new AsccpManifestId(BigInteger.valueOf(4)));
        when(targetAscc.toAsccpManifestId()).thenReturn(new AsccpManifestId(BigInteger.valueOf(20)));
        when(sourceCcDocument.getAsccp(any(AsccpManifestId.class))).thenReturn(sourceAsccp);
        when(targetCcDocument.getAsccp(any(AsccpManifestId.class))).thenReturn(targetAsccp);
        when(sourceAsccp.roleOfAccManifestId()).thenReturn(new AccManifestId(BigInteger.valueOf(5)));
        when(targetAsccp.roleOfAccManifestId()).thenReturn(new AccManifestId(BigInteger.valueOf(25)));

        when(sourceCcDocument.getBcc(any(BccManifestId.class))).thenReturn(sourceBcc);
        when(targetCcDocument.getBcc(any(BccManifestId.class))).thenReturn(targetBcc);
        when(sourceBcc.toBccpManifestId()).thenReturn(new BccpManifestId(BigInteger.valueOf(9)));
        when(targetBcc.toBccpManifestId()).thenReturn(new BccpManifestId(BigInteger.valueOf(23)));

        BieUpliftingMapping asccMapping = mapping(
                "ASCCP-4>ASCC-7", "ASCCP-20>ASCC-21");
        BieUpliftingMapping bccMapping = mapping(
                "ASCCP-4>ACC-6>BCC-8", "ASCCP-20>ACC-25>BCC-22");
        BieUpliftingMapping dtScMapping = mapping(
                "ASCCP-4>ACC-6>BCC-8>DT-9>DT_SC-10", "TARGET>DT_SC-19");
        BieUpliftingCustomMappingTable table = new BieUpliftingCustomMappingTable(
                sourceCcDocument, targetCcDocument, List.of(asccMapping, bccMapping, dtScMapping));

        assertThat(table.getTargetAsccMappingBySourcePath(
                "ASCCP-1>ACC-2>ASCCP-4>ACC-5>ASCC-7")).isSameAs(asccMapping);
        assertThat(table.getTargetBccMappingBySourcePath(
                "ASCCP-1>ACC-2>ASCCP-4>ACC-5>ACC-6>BCC-8")).isSameAs(bccMapping);
        assertThat(table.getTargetDtScMappingBySourcePath(
                "ASCCP-1>ACC-2>ASCCP-4>ACC-5>ACC-6>BCC-8>DT-9>DT_SC-10"))
                .isSameAs(dtScMapping);
    }

    private static BieUpliftingMapping mapping(String sourcePath, String targetPath) {
        BieUpliftingMapping mapping = new BieUpliftingMapping();
        mapping.setSourcePath(sourcePath);
        mapping.setTargetPath(targetPath);
        return mapping;
    }
}
