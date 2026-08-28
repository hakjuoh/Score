package org.oagi.score.gateway.http.api.bie_management.model;

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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.oagi.score.gateway.http.common.util.StringUtils.hasLength;

public class BieUpliftingCustomMappingTable {

    private List<BieUpliftingMapping> mappingList;

    private Map<String, BieUpliftingMapping> targetAsccMappingMap;
    private Map<String, BieUpliftingMapping> targetAsccMappingByTargetPathMap;
    private Map<String, AsccpManifestId> targetAsccpManifestIdBySourcePathMap;
    private Map<String, AccManifestId> targetAccManifestIdBySourcePathMap;
    private Map<String, BieUpliftingMapping> targetBccMappingMap;
    private Map<String, BccpManifestId> targetBccpManifestIdBySourcePathMap;
    private Map<String, BieUpliftingMapping> targetDtScMappingMap;
    private Map<String, BieUpliftingMapping> targetAsccMappingLegacyPathMap;
    private Map<String, BieUpliftingMapping> targetBccMappingLegacyPathMap;
    private Map<String, BieUpliftingMapping> targetDtScMappingLegacyPathMap;

    public BieUpliftingCustomMappingTable(CcDocument sourceCcDocument,
                                          CcDocument targetCcDocument,
                                          List<BieUpliftingMapping> mappingList) {
        if (mappingList == null) {
            throw new IllegalArgumentException();
        }

        this.mappingList = mappingList;

        targetAsccMappingMap = mappingList.stream()
                .filter(e -> hasLength(e.getSourcePath()))
                .filter(e -> getLastTag(e.getSourcePath()).contains("ASCC"))
                .collect(Collectors.toMap(BieUpliftingMapping::getSourcePath, Function.identity(), (a1, a2) -> a2));
        targetAsccMappingLegacyPathMap = buildLegacyPathMap(targetAsccMappingMap);

        targetAsccMappingByTargetPathMap = mappingList.stream()
                .filter(e -> hasLength(e.getSourcePath()) && hasLength(e.getTargetPath()))
                .filter(e -> getLastTag(e.getTargetPath()).contains("ASCC"))
                .collect(Collectors.toMap(BieUpliftingMapping::getTargetPath, Function.identity(), (a1, a2) -> a2));

        targetAsccpManifestIdBySourcePathMap = targetAsccMappingMap.values().stream()
                .filter(e -> hasLength(e.getSourcePath()) && hasLength(e.getTargetPath()))
                .collect(Collectors.toMap(e -> {
                    AsccManifestId sourceAsccManifestId = new AsccManifestId(extractManifestId(getLastTag(e.getSourcePath())));
                    AsccSummaryRecord sourceAscc = sourceCcDocument.getAscc(sourceAsccManifestId);
                    return e.getSourcePath() + ">" + "ASCCP-" + sourceAscc.toAsccpManifestId();
                }, e -> {
                    AsccManifestId targetAsccManifestId = new AsccManifestId(extractManifestId(getLastTag(e.getTargetPath())));
                    AsccSummaryRecord targetAsccManifest = targetCcDocument.getAscc(targetAsccManifestId);
                    return targetAsccManifest.toAsccpManifestId();
                }, (a1, a2) -> a2));

        targetAccManifestIdBySourcePathMap = targetAsccMappingMap.values().stream()
                .filter(e -> hasLength(e.getSourcePath()) && hasLength(e.getTargetPath()))
                .collect(Collectors.toMap(e -> {
                    AsccManifestId sourceAsccManifestId = new AsccManifestId(extractManifestId(getLastTag(e.getSourcePath())));
                    AsccSummaryRecord sourceAscc = sourceCcDocument.getAscc(sourceAsccManifestId);
                    AsccpSummaryRecord sourceAsccp = sourceCcDocument.getAsccp(sourceAscc.toAsccpManifestId());
                    return e.getSourcePath() + ">" + "ASCCP-" + sourceAscc.toAsccpManifestId() +
                            ">" + "ACC-" + sourceAsccp.roleOfAccManifestId();
                }, e -> {
                    AsccManifestId targetAsccManifestId = new AsccManifestId(extractManifestId(getLastTag(e.getTargetPath())));
                    AsccSummaryRecord targetAscc = targetCcDocument.getAscc(targetAsccManifestId);
                    AsccpSummaryRecord targetAsccp = targetCcDocument.getAsccp(targetAscc.toAsccpManifestId());
                    return targetAsccp.roleOfAccManifestId();
                }, (a1, a2) -> a2));

        targetBccMappingMap = mappingList.stream()
                .filter(e -> hasLength(e.getSourcePath()))
                .filter(e -> getLastTag(e.getSourcePath()).contains("BCC"))
                .collect(Collectors.toMap(BieUpliftingMapping::getSourcePath, Function.identity(), (a1, a2) -> a2));
        targetBccMappingLegacyPathMap = buildLegacyPathMap(targetBccMappingMap);

        targetBccpManifestIdBySourcePathMap = targetBccMappingMap.values().stream()
                .filter(e -> hasLength(e.getSourcePath()) && hasLength(e.getTargetPath()))
                .collect(Collectors.toMap(e -> {
                    BccManifestId sourceBccManifestId = new BccManifestId(extractManifestId(getLastTag(e.getSourcePath())));
                    BccSummaryRecord sourceBcc = sourceCcDocument.getBcc(sourceBccManifestId);
                    return e.getSourcePath() + ">" + "BCCP-" + sourceBcc.toBccpManifestId();
                }, e -> {
                    BccManifestId targetBccManifestId = new BccManifestId(extractManifestId(getLastTag(e.getTargetPath())));
                    BccSummaryRecord targetBcc = targetCcDocument.getBcc(targetBccManifestId);
                    return targetBcc.toBccpManifestId();
                }, (a1, a2) -> a2));

        targetDtScMappingMap = mappingList.stream()
                .filter(e -> hasLength(e.getSourcePath()))
                .filter(e -> getLastTag(e.getSourcePath()).contains("DT_SC"))
                .collect(Collectors.toMap(BieUpliftingMapping::getSourcePath, Function.identity(), (a1, a2) -> a2));
        targetDtScMappingLegacyPathMap = buildLegacyPathMap(targetDtScMappingMap);
    }

    private Map<String, BieUpliftingMapping> buildLegacyPathMap(
            Map<String, BieUpliftingMapping> mappingMap) {
        return mappingMap.values().stream()
                .collect(Collectors.toMap(e -> legacySourcePath(e.getSourcePath()), Function.identity(), (a1, a2) -> a2));
    }

    /**
     * Returns the path shape emitted by clients before reused subtrees used the full visitor path.
     * A legacy path starts at the nearest ASCCP and omits its role ACC; intermediate ACC nodes
     * remain because they identify the association's actual structural location.
     */
    public static String legacySourcePath(String path) {
        if (!hasLength(path)) {
            return path;
        }

        String[] tags = path.split(">");
        int lastAsccpIndex = -1;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].startsWith("ASCCP-")) {
                lastAsccpIndex = i;
            }
        }
        if (lastAsccpIndex <= 0) {
            return path;
        }

        StringBuilder legacyPath = new StringBuilder();
        for (int i = lastAsccpIndex; i < tags.length; i++) {
            if (i == lastAsccpIndex + 1 && tags[i].startsWith("ACC-")) {
                continue;
            }
            if (legacyPath.length() > 0) {
                legacyPath.append('>');
            }
            legacyPath.append(tags[i]);
        }
        return legacyPath.toString();
    }

    public static String getLastTag(String path) {
        if (path == null) {
            return null;
        }
        String[] tags = path.split(">");
        return tags[tags.length - 1];
    }

    public static BigInteger extractManifestId(String tag) {
        return new BigInteger(tag.substring(tag.indexOf('-') + 1));
    }

    public AccManifestId getTargetAccManifestIdBySourcePath(String sourcePath) {
        return targetAccManifestIdBySourcePathMap.get(sourcePath);
    }

    public AsccpManifestId getTargetAsccpManifestIdBySourcePath(String sourcePath) {
        return targetAsccpManifestIdBySourcePathMap.get(sourcePath);
    }

    public BccpManifestId getTargetBccpManifestIdBySourcePath(String sourcePath) {
        return targetBccpManifestIdBySourcePathMap.get(sourcePath);
    }

    public BieUpliftingMapping getTargetAsccMappingBySourcePath(String sourcePath) {
        return getMapping(targetAsccMappingMap, targetAsccMappingLegacyPathMap, sourcePath);
    }

    public BieUpliftingMapping getTargetAsccMappingByTargetPath(String targetPath) {
        return targetAsccMappingByTargetPathMap.get(targetPath);
    }

    public BieUpliftingMapping getTargetBccMappingBySourcePath(String sourcePath) {
        return getMapping(targetBccMappingMap, targetBccMappingLegacyPathMap, sourcePath);
    }

    public BieUpliftingMapping getTargetDtScMappingBySourcePath(String sourcePath) {
        return getMapping(targetDtScMappingMap, targetDtScMappingLegacyPathMap, sourcePath);
    }

    private BieUpliftingMapping getMapping(Map<String, BieUpliftingMapping> mappingMap,
                                           Map<String, BieUpliftingMapping> legacyPathMap,
                                           String sourcePath) {
        BieUpliftingMapping mapping = mappingMap.get(sourcePath);
        return mapping != null ? mapping : legacyPathMap.get(legacySourcePath(sourcePath));
    }

    public List<BieUpliftingMapping> getMappingList() {
        return this.mappingList;
    }

}
