package org.oagi.score.gateway.http.api.release_management.repository.jooq;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.types.UInteger;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.CcType;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListManifestId;
import org.oagi.score.gateway.http.api.library_management.model.LibraryId;
import org.oagi.score.gateway.http.api.log_management.model.LogAction;
import org.oagi.score.gateway.http.api.log_management.model.LogId;
import org.oagi.score.gateway.http.api.log_management.model.LogSummaryRecord;
import org.oagi.score.gateway.http.api.log_management.model.LogUtils;
import org.oagi.score.gateway.http.api.log_management.repository.LogRepository;
import org.oagi.score.gateway.http.api.log_management.service.LogSerializer;
import org.oagi.score.gateway.http.api.namespace_management.model.NamespaceId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseExport;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseImport;
import org.oagi.score.gateway.http.api.release_management.repository.ReleaseImportCommandRepository;
import org.oagi.score.gateway.http.api.xbt_management.model.XbtManifestId;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.*;
import static org.oagi.score.gateway.http.common.util.ScoreGuidUtils.randomGuid;

public class JooqReleaseImportCommandRepository extends JooqBaseRepository implements ReleaseImportCommandRepository {

    private static final BigInteger END_USER_ID_FLOOR = BigInteger.valueOf(100000000L);
    private static final String WORKING_RELEASE_NUM = "Working";
    private final Map<String, BigInteger> nextDeveloperIdByTable = new HashMap<>();

    private record ExistingRelease(
            BigInteger releaseId,
            String libraryName,
            String releaseNum,
            boolean matchedByGuid,
            boolean matchedByReleaseNum) {

        private String message() {
            return libraryName + " " + releaseNum + " release already exists.";
        }
    }

    private record MissingDependency(
            String libraryName,
            String releaseNum,
            String message) {
    }

    public JooqReleaseImportCommandRepository(DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, requester, repositoryFactory);
    }

    @Override
    public ReleaseImport.Check checkImport(ReleaseImport.Bundle bundle) {
        ImportContext context = new ImportContext(bundle);
        validateBundle(context);

        MissingDependency missingDependency = findMissingDependency(context);
        ExistingRelease existingRelease = inspectExistingRelease(context);
        return new ReleaseImport.Check(
                missingDependency != null,
                existingRelease != null,
                (existingRelease != null) ? new ReleaseId(existingRelease.releaseId()) : null,
                releaseLibraryName(context),
                releaseNum(context),
                (missingDependency != null) ? missingDependency.message() :
                        ((existingRelease != null) ? existingRelease.message() : null));
    }

    @Override
    public ReleaseId importRelease(ReleaseImport.Bundle bundle) {
        ImportContext context = new ImportContext(bundle);
        validateBundle(context);
        nextDeveloperIdByTable.clear();

        ExistingRelease existingRelease = inspectExistingRelease(context);
        if (existingRelease != null && !context.overwrite()) {
            throw new IllegalArgumentException(existingRelease.message());
        }

        context.libraryId = resolveLibrary(context);
        context.namespaceId = resolveNamespace(context);
        context.workingReleaseId = ensureWorkingRelease(context);
        validateDependency(context);
        context.releaseId = ensureRelease(context);
        linkReleaseChain(context);

        importTags(context);
        importModuleSets(context);

        importXbts(context);
        importDts(context);
        importAccs(context);
        importAsccps(context);
        importBccps(context);
        importAsccs(context);
        importBccs(context);
        importDtScs(context);
        importCodeLists(context);
        importCodeListValues(context);
        importAgencyIdLists(context);
        importAgencyIdListValues(context);
        importBlobContents(context);
        importModuleSetReleases(context);
        importModules(context);

        updateXbtLinks(context);
        updateDtLinks(context);
        updateAccLinks(context);
        updateAsccpLinks(context);
        updateBccpLinks(context);
        updateAsccLinks(context);
        updateBccLinks(context);
        updateDtScLinks(context);
        updateCodeListLinks(context);
        updateCodeListValueLinks(context);
        updateAgencyIdListLinks(context);
        updateAgencyIdListValueLinks(context);
        updateModuleLinks(context);

        importXbtManifests(context);
        importDtManifests(context);
        importAccManifests(context);
        importAsccpManifests(context);
        importBccpManifests(context);
        importDtScManifests(context);
        importAsccManifests(context);
        importBccManifests(context);
        importCodeListManifests(context);
        importCodeListValueManifests(context);
        importAgencyIdListManifests(context);
        importAgencyIdListValueManifests(context);
        importBlobContentManifests(context);

        updateXbtManifestLinks(context);
        updateDtManifestLinks(context);
        updateAccManifestLinks(context);
        updateAsccpManifestLinks(context);
        updateBccpManifestLinks(context);
        updateDtScManifestLinks(context);
        updateAsccManifestLinks(context);
        updateBccManifestLinks(context);
        updateCodeListManifestLinks(context);
        updateCodeListValueManifestLinks(context);
        updateAgencyIdListManifestLinks(context);
        updateAgencyIdListValueManifestLinks(context);
        updateBlobContentManifestLinks(context);

        importSeqKeys(context);
        updateSeqKeyLinks(context);

        importDtAwdPris(context);
        importDtScAwdPris(context);
        importReleaseDeps(context);
        importAccManifestTags(context);
        importAsccpManifestTags(context);
        importBccpManifestTags(context);
        importDtManifestTags(context);
        importModuleAccManifests(context);
        importModuleAgencyIdListManifests(context);
        importModuleAsccpManifests(context);
        importModuleBccpManifests(context);
        importModuleCodeListManifests(context);
        importModuleDtManifests(context);
        importModuleXbtManifests(context);
        importModuleBlobContentManifests(context);
        ensureManifestLogs(context);
        syncWorkingRelease(context);

        return new ReleaseId(context.releaseId);
    }

    private void ensureManifestLogs(ImportContext context) {
        LogRepository logRepository = new LogRepository(dslContext(), repositoryFactory());
        ensureXbtManifestLogs(context, logRepository);
        ensureDtManifestLogs(context, logRepository);
        ensureAccManifestLogs(context, logRepository);
        ensureAsccpManifestLogs(context, logRepository);
        ensureBccpManifestLogs(context, logRepository);
        ensureCodeListManifestLogs(context, logRepository);
        ensureAgencyIdListManifestLogs(context, logRepository);
    }

    private void syncWorkingRelease(ImportContext context) {
        if (!"Published".equalsIgnoreCase(string(context.releaseRow(), "state"))
                || context.workingReleaseId == null
                || context.releaseId == null
                || context.workingReleaseId.equals(context.releaseId)) {
            return;
        }

        ReleaseId workingReleaseId = new ReleaseId(context.workingReleaseId);
        repositoryFactory().ccCommandRepository(requester()).delete(workingReleaseId);
        repositoryFactory().releaseCommandRepository(requester()).deleteDeps(workingReleaseId);

        ImportContext workingContext = new ImportContext(new ReleaseImport.Bundle(
                context.metadata(),
                buildWorkingPayloads(context),
                true));
        workingContext.libraryId = context.libraryId;
        workingContext.namespaceId = context.namespaceId;
        workingContext.releaseId = context.workingReleaseId;
        copyRawMaps(context, workingContext);

        importXbtManifests(workingContext);
        importDtManifests(workingContext);
        importAccManifests(workingContext);
        importAsccpManifests(workingContext);
        importBccpManifests(workingContext);
        importDtScManifests(workingContext);
        importAsccManifests(workingContext);
        importBccManifests(workingContext);
        importCodeListManifests(workingContext);
        importCodeListValueManifests(workingContext);
        importAgencyIdListManifests(workingContext);
        importAgencyIdListValueManifests(workingContext);
        importBlobContentManifests(workingContext);

        updateXbtManifestLinks(workingContext);
        updateDtManifestLinks(workingContext);
        updateAccManifestLinks(workingContext);
        updateAsccpManifestLinks(workingContext);
        updateBccpManifestLinks(workingContext);
        updateDtScManifestLinks(workingContext);
        updateAsccManifestLinks(workingContext);
        updateBccManifestLinks(workingContext);
        updateCodeListManifestLinks(workingContext);
        updateCodeListValueManifestLinks(workingContext);
        updateAgencyIdListManifestLinks(workingContext);
        updateAgencyIdListValueManifestLinks(workingContext);
        updateBlobContentManifestLinks(workingContext);

        importSeqKeys(workingContext);
        updateSeqKeyLinks(workingContext);
        importDtAwdPris(workingContext);
        importDtScAwdPris(workingContext);
        importReleaseDeps(workingContext);
        importAccManifestTags(workingContext);
        importAsccpManifestTags(workingContext);
        importBccpManifestTags(workingContext);
        importDtManifestTags(workingContext);
        ensureManifestLogs(workingContext);
        updatePublishedManifestNextLinks(context, workingContext);
    }

    private Map<String, List<Map<String, Object>>> buildWorkingPayloads(ImportContext context) {
        Map<String, List<Map<String, Object>>> payloadsByTable = new LinkedHashMap<>();
        for (String table : List.of(
                "xbt_manifest",
                "dt_manifest",
                "acc_manifest",
                "asccp_manifest",
                "bccp_manifest",
                "dt_sc_manifest",
                "ascc_manifest",
                "bcc_manifest",
                "code_list_manifest",
                "code_list_value_manifest",
                "agency_id_list_manifest",
                "agency_id_list_value_manifest",
                "blob_content_manifest",
                "seq_key",
                "dt_awd_pri",
                "dt_sc_awd_pri",
                "release_dep",
                "acc_manifest_tag",
                "asccp_manifest_tag",
                "bccp_manifest_tag",
                "dt_manifest_tag")) {
            List<Map<String, Object>> rows = context.rows(table);
            if (!rows.isEmpty()) {
                payloadsByTable.put(table, cloneWorkingRows(context, table, rows));
            }
        }
        return payloadsByTable;
    }

    private List<Map<String, Object>> cloneWorkingRows(ImportContext context, String table, List<Map<String, Object>> rows) {
        if (!table.endsWith("_manifest")) {
            return rows.stream()
                    .map(LinkedHashMap::new)
                    .map(row -> (Map<String, Object>) row)
                    .toList();
        }

        String publishedReleaseGuid = string(context.releaseRow(), "guid");
        String workingReleaseGuid = findString(RELEASE, RELEASE.GUID, RELEASE.RELEASE_ID.eq(toULong(context.workingReleaseId)));
        String mainGuidKey = mainManifestGuidKey(table);

        return rows.stream().map(row -> {
            LinkedHashMap<String, Object> cloned = new LinkedHashMap<>(row);
            String ownGuid = string(row, mainGuidKey);
            for (String key : new ArrayList<>(cloned.keySet())) {
                if (key.startsWith("next_") && key.endsWith("_guid")) {
                    cloned.put(key, null);
                    continue;
                }
                if (key.startsWith("next_") && key.endsWith("_release_guid")) {
                    cloned.put(key, null);
                    continue;
                }
                if (key.startsWith("prev_") && key.endsWith("_guid")) {
                    cloned.put(key, ownGuid);
                    continue;
                }
                if (key.startsWith("prev_") && key.endsWith("_release_guid")) {
                    cloned.put(key, publishedReleaseGuid);
                    continue;
                }
                if (key.endsWith("_release_guid")
                        && Objects.equals(cloned.get(key), publishedReleaseGuid)
                        && StringUtils.hasText(workingReleaseGuid)) {
                    cloned.put(key, workingReleaseGuid);
                }
            }
            return (Map<String, Object>) cloned;
        }).toList();
    }

    private String mainManifestGuidKey(String table) {
        return switch (table) {
            case "xbt_manifest" -> "xbt_guid";
            case "dt_manifest" -> "dt_guid";
            case "acc_manifest" -> "acc_guid";
            case "asccp_manifest" -> "asccp_guid";
            case "bccp_manifest" -> "bccp_guid";
            case "dt_sc_manifest" -> "dt_sc_guid";
            case "ascc_manifest" -> "ascc_guid";
            case "bcc_manifest" -> "bcc_guid";
            case "code_list_manifest" -> "code_list_guid";
            case "code_list_value_manifest" -> "code_list_value_guid";
            case "agency_id_list_manifest" -> "agency_id_list_guid";
            case "agency_id_list_value_manifest" -> "agency_id_list_value_guid";
            default -> "";
        };
    }

    private void copyRawMaps(ImportContext source, ImportContext target) {
        for (String table : List.of(
                "xbt",
                "dt",
                "acc",
                "asccp",
                "bccp",
                "dt_sc",
                "ascc",
                "bcc",
                "code_list",
                "code_list_value",
                "agency_id_list",
                "agency_id_list_value")) {
            target.idByGuid(table).putAll(source.idByGuid(table));
        }
        target.idByInternal("blob_content").putAll(source.idByInternal("blob_content"));
        target.idByName("tag").putAll(source.idByName("tag"));
    }

    private void updatePublishedManifestNextLinks(ImportContext releaseContext, ImportContext workingContext) {
        updatePublishedManifestNextLinks(releaseContext, workingContext, "xbt_manifest", XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, XBT_MANIFEST.PREV_XBT_MANIFEST_ID, XBT_MANIFEST.NEXT_XBT_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "dt_manifest", DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.PREV_DT_MANIFEST_ID, DT_MANIFEST.NEXT_DT_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "acc_manifest", ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.PREV_ACC_MANIFEST_ID, ACC_MANIFEST.NEXT_ACC_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "asccp_manifest", ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, ASCCP_MANIFEST.PREV_ASCCP_MANIFEST_ID, ASCCP_MANIFEST.NEXT_ASCCP_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "bccp_manifest", BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, BCCP_MANIFEST.PREV_BCCP_MANIFEST_ID, BCCP_MANIFEST.NEXT_BCCP_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "dt_sc_manifest", DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, DT_SC_MANIFEST.PREV_DT_SC_MANIFEST_ID, DT_SC_MANIFEST.NEXT_DT_SC_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "ascc_manifest", ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, ASCC_MANIFEST.PREV_ASCC_MANIFEST_ID, ASCC_MANIFEST.NEXT_ASCC_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "bcc_manifest", BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, BCC_MANIFEST.PREV_BCC_MANIFEST_ID, BCC_MANIFEST.NEXT_BCC_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "code_list_manifest", CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.PREV_CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.NEXT_CODE_LIST_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "code_list_value_manifest", CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, CODE_LIST_VALUE_MANIFEST.PREV_CODE_LIST_VALUE_MANIFEST_ID, CODE_LIST_VALUE_MANIFEST.NEXT_CODE_LIST_VALUE_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "agency_id_list_manifest", AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.PREV_AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.NEXT_AGENCY_ID_LIST_MANIFEST_ID);
        updatePublishedManifestNextLinks(releaseContext, workingContext, "agency_id_list_value_manifest", AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, AGENCY_ID_LIST_VALUE_MANIFEST.PREV_AGENCY_ID_LIST_VALUE_MANIFEST_ID, AGENCY_ID_LIST_VALUE_MANIFEST.NEXT_AGENCY_ID_LIST_VALUE_MANIFEST_ID);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void updatePublishedManifestNextLinks(
            ImportContext releaseContext,
            ImportContext workingContext,
            String tableKey,
            Table table,
            TableField idField,
            TableField prevField,
            TableField nextField) {
        for (Map.Entry<String, BigInteger> entry : releaseContext.idByGuid(tableKey).entrySet()) {
            BigInteger workingId = workingContext.idByGuid(tableKey).get(entry.getKey());
            if (workingId == null) {
                continue;
            }
            updateRowById(table, idField, entry.getValue(), mapOf(nextField, toULong(workingId)));
            updateRowById(table, idField, workingId, mapOf(prevField, toULong(entry.getValue())));
        }
    }

    private void ensureXbtManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().xbtQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("xbt_manifest")) {
            String reference = string(row, "xbt_guid");
            BigInteger manifestId = resolveGuidId(context, "xbt_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.XBT);
            updateRowById(XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, manifestId, mapOf(XBT_MANIFEST.LOG_ID, prevLogId));
            var xbtDetails = query.getXbtDetails(new XbtManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), xbtDetails),
                    xbtDetails.guid());
            updateRowById(XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, manifestId, mapOf(XBT_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private void ensureDtManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().dtQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("dt_manifest")) {
            String reference = string(row, "dt_guid");
            BigInteger manifestId = resolveGuidId(context, "dt_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.DT);
            updateRowById(DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, manifestId, mapOf(DT_MANIFEST.LOG_ID, prevLogId));
            var dtDetails = query.getDtDetails(new DtManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), dtDetails),
                    dtDetails.guid());
            updateRowById(DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, manifestId, mapOf(DT_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private void ensureAccManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().accQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("acc_manifest")) {
            String reference = string(row, "acc_guid");
            BigInteger manifestId = resolveGuidId(context, "acc_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.ACC);
            updateRowById(ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, manifestId, mapOf(ACC_MANIFEST.LOG_ID, prevLogId));
            var accDetails = query.getAccDetails(new AccManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), accDetails),
                    accDetails.guid());
            updateRowById(ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, manifestId, mapOf(ACC_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private void ensureAsccpManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().asccpQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("asccp_manifest")) {
            String reference = string(row, "asccp_guid");
            BigInteger manifestId = resolveGuidId(context, "asccp_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.ASCCP);
            updateRowById(ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, manifestId, mapOf(ASCCP_MANIFEST.LOG_ID, prevLogId));
            var asccpDetails = query.getAsccpDetails(new AsccpManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), asccpDetails),
                    asccpDetails.guid());
            updateRowById(ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, manifestId, mapOf(ASCCP_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private void ensureBccpManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().bccpQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("bccp_manifest")) {
            String reference = string(row, "bccp_guid");
            BigInteger manifestId = resolveGuidId(context, "bccp_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.BCCP);
            updateRowById(BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, manifestId, mapOf(BCCP_MANIFEST.LOG_ID, prevLogId));
            var bccpDetails = query.getBccpDetails(new BccpManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), bccpDetails),
                    bccpDetails.guid());
            updateRowById(BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, manifestId, mapOf(BCCP_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private void ensureCodeListManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().codeListQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("code_list_manifest")) {
            String reference = string(row, "code_list_guid");
            BigInteger manifestId = resolveGuidId(context, "code_list_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.CODE_LIST);
            updateRowById(CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, manifestId, mapOf(CODE_LIST_MANIFEST.LOG_ID, prevLogId));
            var codeListDetails = query.getCodeListDetails(new CodeListManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), codeListDetails),
                    codeListDetails.guid());
            updateRowById(CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, manifestId, mapOf(CODE_LIST_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private void ensureAgencyIdListManifestLogs(ImportContext context, LogRepository logRepository) {
        var query = repositoryFactory().agencyIdListQueryRepository(requester());
        LogSerializer serializer = repositoryFactory().logSerializer();
        for (Map<String, Object> row : context.rows("agency_id_list_manifest")) {
            String reference = string(row, "agency_id_list_guid");
            BigInteger manifestId = resolveGuidId(context, "agency_id_list_manifest", reference);
            if (manifestId == null || !StringUtils.hasText(reference)) {
                continue;
            }
            ULong prevLogId = latestLogId(logRepository, reference, CcType.AGENCY_ID_LIST);
            updateRowById(AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, manifestId, mapOf(AGENCY_ID_LIST_MANIFEST.LOG_ID, prevLogId));
            var agencyIdListDetails = query.getAgencyIdListDetails(new AgencyIdListManifestId(manifestId));
            LogId logId = createImportedLog(prevLogId,
                    (prevLogId == null) ? LogAction.Added : LogAction.Modified,
                    LogUtils.generateHash(),
                    serializer.serialize(requester(), agencyIdListDetails),
                    agencyIdListDetails.guid());
            updateRowById(AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, manifestId, mapOf(AGENCY_ID_LIST_MANIFEST.LOG_ID, valueOf(logId)));
        }
    }

    private ULong latestLogId(LogRepository logRepository, String reference, CcType ccType) {
        List<org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.LogRecord> sortedLogs =
                logRepository.getSortedLogListByReference(reference, org.oagi.score.gateway.http.common.model.SortDirection.DESC, ccType);
        if (sortedLogs == null || sortedLogs.isEmpty()) {
            return null;
        }
        return sortedLogs.get(0).getLogId();
    }

    private LogId createImportedLog(ULong prevLogId,
                                    LogAction logAction,
                                    String logHash,
                                    String serializedString,
                                    Guid reference) {
        org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.LogRecord prevLogRecord = null;
        if (prevLogId != null) {
            prevLogRecord = dslContext().selectFrom(LOG)
                    .where(LOG.LOG_ID.eq(prevLogId))
                    .fetchOne();
        }

        org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.LogRecord logRecord =
                dslContext().newRecord(LOG);
        logRecord.setLogId(toULong(nextDeveloperId(LOG, LOG.LOG_ID)));
        logRecord.setHash(logHash);
        if (LogAction.Revised.equals(logAction)) {
            assert (prevLogRecord != null);
            logRecord.setRevisionNum(prevLogRecord.getRevisionNum().add(1));
            logRecord.setRevisionTrackingNum(UInteger.valueOf(1));
        } else if (LogAction.Canceled.equals(logAction)) {
            assert (prevLogRecord != null);
            logRecord.setRevisionNum(prevLogRecord.getRevisionNum().subtract(1));
            logRecord.setRevisionTrackingNum(UInteger.valueOf(1));
        } else {
            if (prevLogRecord != null) {
                logRecord.setRevisionNum(prevLogRecord.getRevisionNum());
                logRecord.setRevisionTrackingNum(prevLogRecord.getRevisionTrackingNum().add(1));
            } else {
                logRecord.setRevisionNum(UInteger.valueOf(1));
                logRecord.setRevisionTrackingNum(UInteger.valueOf(1));
            }
        }
        logRecord.setLogAction(logAction.name());
        logRecord.setSnapshot(serializedString);
        logRecord.setReference(reference.value());
        logRecord.setCreatedBy(valueOf(requester().userId()));
        logRecord.setCreationTimestamp(LocalDateTime.now());
        if (prevLogRecord != null) {
            logRecord.setPrevLogId(prevLogRecord.getLogId());
        }

        dslContext().insertInto(LOG).set(logRecord).execute();
        if (prevLogRecord != null) {
            prevLogRecord.setNextLogId(logRecord.getLogId());
            prevLogRecord.update(LOG.NEXT_LOG_ID);
        }

        return new LogId(logRecord.getLogId().toBigInteger());
    }

    private void validateBundle(ImportContext context) {
        if (context.metadata() == null) {
            throw new IllegalArgumentException("Release package metadata is missing.");
        }
        if (!"score-release-export".equals(context.metadata().format())) {
            throw new IllegalArgumentException("Unsupported release package format.");
        }
        if (context.releaseRow() == null) {
            throw new IllegalArgumentException("Release payload is missing.");
        }
    }

    private ExistingRelease inspectExistingRelease(ImportContext context) {
        BigInteger libraryId = findLibraryId(context);
        if (libraryId == null) {
            return null;
        }

        Map<String, Object> releaseRow = context.releaseRow();
        String guid = string(releaseRow, "guid");
        String releaseNum = releaseNum(context);

        if (StringUtils.hasText(guid)) {
            BigInteger existingId = findId(RELEASE, RELEASE.RELEASE_ID, RELEASE.GUID.eq(guid));
            if (existingId != null) {
                return new ExistingRelease(existingId, releaseLibraryName(context), releaseNum, true, true);
            }
        }

        if (StringUtils.hasText(releaseNum)) {
            BigInteger existingId = findId(RELEASE, RELEASE.RELEASE_ID,
                    RELEASE.LIBRARY_ID.eq(toULong(libraryId))
                            .and(RELEASE.RELEASE_NUM.eq(releaseNum)));
            if (existingId != null) {
                return new ExistingRelease(existingId, releaseLibraryName(context), releaseNum, false, true);
            }
        }

        return null;
    }

    private BigInteger findLibraryId(ImportContext context) {
        Map<String, Object> row = context.firstRow("library");
        ReleaseExport.LibraryKey libraryKey = context.metadata().source().library();

        Condition condition = stringEquals(LIBRARY.NAME, value(row, "name", libraryKey.name()))
                .and(stringEquals(LIBRARY.ORGANIZATION, value(row, "organization", libraryKey.organization())))
                .and(stringEquals(LIBRARY.TYPE, value(row, "type", libraryKey.type())))
                .and(stringEquals(LIBRARY.DOMAIN, value(row, "domain", libraryKey.domain())));

        return findId(LIBRARY, LIBRARY.LIBRARY_ID, condition);
    }

    private String releaseLibraryName(ImportContext context) {
        return context.metadata().source().library().name();
    }

    private String releaseNum(ImportContext context) {
        return string(context.releaseRow(), "release_num");
    }

    private BigInteger resolveLibrary(ImportContext context) {
        Map<String, Object> row = context.firstRow("library");
        ReleaseExport.LibraryKey libraryKey = context.metadata().source().library();

        BigInteger existingId = findLibraryId(context);
        if (existingId != null) {
            LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
            values.put(LIBRARY.DESCRIPTION, string(row, "description"));
            values.put(LIBRARY.LINK, string(row, "link"));
            values.put(LIBRARY.STATE, string(row, "state"));
            values.put(LIBRARY.IS_READ_ONLY, bit(row, "read_only"));
            if (row != null && row.containsKey("is_default")) {
                values.put(LIBRARY.IS_DEFAULT, bit(row, "is_default"));
            }
            updateRowById(LIBRARY, LIBRARY.LIBRARY_ID, existingId, values);
            return existingId;
        }

        LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(LIBRARY.NAME, value(row, "name", libraryKey.name()));
        values.put(LIBRARY.ORGANIZATION, value(row, "organization", libraryKey.organization()));
        values.put(LIBRARY.TYPE, value(row, "type", libraryKey.type()));
        values.put(LIBRARY.DOMAIN, value(row, "domain", libraryKey.domain()));
        values.put(LIBRARY.DESCRIPTION, string(row, "description"));
        values.put(LIBRARY.LINK, string(row, "link"));
        values.put(LIBRARY.STATE, string(row, "state"));
        values.put(LIBRARY.IS_READ_ONLY, bit(row, "read_only"));
        values.put(LIBRARY.IS_DEFAULT, bit(row, "is_default"));
        values.put(LIBRARY.CREATED_BY, valueOf(requester().userId()));
        values.put(LIBRARY.LAST_UPDATED_BY, valueOf(requester().userId()));
        values.put(LIBRARY.CREATION_TIMESTAMP, LocalDateTime.now());
        values.put(LIBRARY.LAST_UPDATE_TIMESTAMP, LocalDateTime.now());
        return insertRow(LIBRARY, LIBRARY.LIBRARY_ID, internalId(row), values);
    }

    private BigInteger resolveNamespace(ImportContext context) {
        Map<String, Object> row = context.firstRow("namespace");
        if (row == null || !StringUtils.hasText(string(row, "uri"))) {
            return null;
        }

        Condition condition = NAMESPACE.LIBRARY_ID.eq(toULong(context.libraryId))
                .and(NAMESPACE.URI.eq(string(row, "uri")));
        BigInteger existingId = findId(NAMESPACE, NAMESPACE.NAMESPACE_ID, condition);
        if (existingId != null) {
            LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
            values.put(NAMESPACE.PREFIX, string(row, "prefix"));
            values.put(NAMESPACE.DESCRIPTION, string(row, "description"));
            values.put(NAMESPACE.IS_STD_NMSP, bit(row, "is_standard"));
            values.put(NAMESPACE.LAST_UPDATED_BY, valueOf(requester().userId()));
            values.put(NAMESPACE.LAST_UPDATE_TIMESTAMP, LocalDateTime.now());
            updateRowById(NAMESPACE, NAMESPACE.NAMESPACE_ID, existingId, values);
            return existingId;
        }

        LocalDateTime now = LocalDateTime.now();
        LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(NAMESPACE.LIBRARY_ID, toULong(context.libraryId));
        values.put(NAMESPACE.URI, string(row, "uri"));
        values.put(NAMESPACE.PREFIX, string(row, "prefix"));
        values.put(NAMESPACE.DESCRIPTION, string(row, "description"));
        values.put(NAMESPACE.IS_STD_NMSP, bit(row, "is_standard"));
        values.put(NAMESPACE.OWNER_USER_ID, valueOf(requester().userId()));
        values.put(NAMESPACE.CREATED_BY, valueOf(requester().userId()));
        values.put(NAMESPACE.LAST_UPDATED_BY, valueOf(requester().userId()));
        values.put(NAMESPACE.CREATION_TIMESTAMP, now);
        values.put(NAMESPACE.LAST_UPDATE_TIMESTAMP, now);
        return insertRow(NAMESPACE, NAMESPACE.NAMESPACE_ID, internalId(row), values);
    }

    private BigInteger ensureWorkingRelease(ImportContext context) {
        BigInteger existingId = findId(RELEASE, RELEASE.RELEASE_ID,
                RELEASE.LIBRARY_ID.eq(toULong(context.libraryId))
                        .and(RELEASE.RELEASE_NUM.eq(WORKING_RELEASE_NUM)));
        LocalDateTime now = LocalDateTime.now();
        if (existingId != null) {
            LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
            values.put(RELEASE.NAMESPACE_ID, nullableULong(context.namespaceId));
            values.put(RELEASE.STATE, "Published");
            values.put(RELEASE.LAST_UPDATED_BY, valueOf(requester().userId()));
            values.put(RELEASE.LAST_UPDATE_TIMESTAMP, now);
            updateRowById(RELEASE, RELEASE.RELEASE_ID, existingId, values);
            return existingId;
        }

        return insertRow(RELEASE, RELEASE.RELEASE_ID, null, mapOf(
                RELEASE.LIBRARY_ID, toULong(context.libraryId),
                RELEASE.GUID, randomGuid(),
                RELEASE.RELEASE_NUM, WORKING_RELEASE_NUM,
                RELEASE.NAMESPACE_ID, nullableULong(context.namespaceId),
                RELEASE.STATE, "Published",
                RELEASE.CREATED_BY, valueOf(requester().userId()),
                RELEASE.LAST_UPDATED_BY, valueOf(requester().userId()),
                RELEASE.CREATION_TIMESTAMP, now,
                RELEASE.LAST_UPDATE_TIMESTAMP, now
        ));
    }

    private void validateDependency(ImportContext context) {
        MissingDependency missingDependency = findMissingDependency(context);
        if (missingDependency != null) {
            throw new IllegalArgumentException(missingDependency.message());
        }
    }

    private MissingDependency findMissingDependency(ImportContext context) {
        Set<String> inspectedReleaseGuids = new HashSet<>();

        ReleaseExport.ReleaseRef dependsOn = context.metadata().source().release().dependsOn();
        MissingDependency missingDependency = findMissingDependency(
                context,
                dependsOn != null ? dependsOn.guid() : null,
                releaseLibraryName(context),
                dependsOn != null ? dependsOn.releaseNum() : null,
                inspectedReleaseGuids);
        if (missingDependency != null) {
            return missingDependency;
        }

        for (Map<String, Object> row : context.rows("release_dep")) {
            missingDependency = findMissingDependency(
                    context,
                    string(row, "depend_on_release_guid"),
                    string(row, "depend_on_library_name"),
                    string(row, "depend_on_release_num"),
                    inspectedReleaseGuids);
            if (missingDependency != null) {
                return missingDependency;
            }
        }

        return null;
    }

    private MissingDependency findMissingDependency(
            ImportContext context,
            String dependencyReleaseGuid,
            String dependencyLibraryName,
            String dependencyReleaseNum,
            Set<String> inspectedReleaseGuids) {

        if (!StringUtils.hasText(dependencyReleaseGuid) || !inspectedReleaseGuids.add(dependencyReleaseGuid)) {
            return null;
        }

        if (findId(RELEASE, RELEASE.RELEASE_ID, RELEASE.GUID.eq(dependencyReleaseGuid)) != null) {
            return null;
        }

        String sourceLibraryName = releaseLibraryName(context);
        String sourceReleaseNum = releaseNum(context);
        String dependencyLabel = StringUtils.hasText(dependencyLibraryName) ?
                dependencyLibraryName + " " + dependencyReleaseNum :
                dependencyReleaseNum;
        return new MissingDependency(
                dependencyLibraryName,
                dependencyReleaseNum,
                dependencyLabel + " release must be imported before importing "
                        + sourceLibraryName + " " + sourceReleaseNum + " release.");
    }

    private BigInteger ensureRelease(ImportContext context) {
        Map<String, Object> row = context.releaseRow();
        String guid = string(row, "guid");
        BigInteger existingId = findId(RELEASE, RELEASE.RELEASE_ID, RELEASE.GUID.eq(guid));
        LocalDateTime now = LocalDateTime.now();
        if (existingId != null) {
            if (!context.overwrite()) {
                throw new IllegalArgumentException(releaseLibraryName(context) + " " + string(row, "release_num") + " release already exists.");
            }
            LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
            values.put(RELEASE.LIBRARY_ID, toULong(context.libraryId));
            values.put(RELEASE.GUID, guid);
            values.put(RELEASE.RELEASE_NUM, string(row, "release_num"));
            values.put(RELEASE.RELEASE_NOTE, string(row, "release_note"));
            values.put(RELEASE.RELEASE_LICENSE, string(row, "release_license"));
            values.put(RELEASE.NAMESPACE_ID, nullableULong(context.namespaceId));
            values.put(RELEASE.STATE, string(row, "state"));
            values.put(RELEASE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))));
            values.put(RELEASE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))));
            values.put(RELEASE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now));
            values.put(RELEASE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now));
            updateRowById(RELEASE, RELEASE.RELEASE_ID, existingId, values);
            return existingId;
        }

        BigInteger sameNumberReleaseId = findId(RELEASE, RELEASE.RELEASE_ID,
                RELEASE.LIBRARY_ID.eq(toULong(context.libraryId))
                        .and(RELEASE.RELEASE_NUM.eq(string(row, "release_num"))));
        if (sameNumberReleaseId != null) {
            if (!context.overwrite()) {
                throw new IllegalArgumentException(releaseLibraryName(context) + " " + string(row, "release_num") + " release already exists.");
            }

            LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
            values.put(RELEASE.LIBRARY_ID, toULong(context.libraryId));
            values.put(RELEASE.GUID, guid);
            values.put(RELEASE.RELEASE_NUM, string(row, "release_num"));
            values.put(RELEASE.RELEASE_NOTE, string(row, "release_note"));
            values.put(RELEASE.RELEASE_LICENSE, string(row, "release_license"));
            values.put(RELEASE.NAMESPACE_ID, nullableULong(context.namespaceId));
            values.put(RELEASE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))));
            values.put(RELEASE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))));
            values.put(RELEASE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now));
            values.put(RELEASE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now));
            values.put(RELEASE.STATE, string(row, "state"));
            updateRowById(RELEASE, RELEASE.RELEASE_ID, sameNumberReleaseId, values);
            return sameNumberReleaseId;
        }

        LinkedHashMap<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(RELEASE.LIBRARY_ID, toULong(context.libraryId));
        values.put(RELEASE.GUID, guid);
        values.put(RELEASE.RELEASE_NUM, string(row, "release_num"));
        values.put(RELEASE.RELEASE_NOTE, string(row, "release_note"));
        values.put(RELEASE.RELEASE_LICENSE, string(row, "release_license"));
        values.put(RELEASE.NAMESPACE_ID, nullableULong(context.namespaceId));
        values.put(RELEASE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))));
        values.put(RELEASE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))));
        values.put(RELEASE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now));
        values.put(RELEASE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now));
        values.put(RELEASE.STATE, string(row, "state"));
        return insertRow(RELEASE, RELEASE.RELEASE_ID, internalId(row), values);
    }

    private void linkReleaseChain(ImportContext context) {
        if (!"Published".equalsIgnoreCase(string(context.releaseRow(), "state"))) {
            return;
        }

        ReleaseExport.ReleaseRef dependsOn = context.metadata().source().release().dependsOn();
        BigInteger prevReleaseId = (dependsOn != null && StringUtils.hasText(dependsOn.guid())) ?
                findId(RELEASE, RELEASE.RELEASE_ID, RELEASE.GUID.eq(dependsOn.guid())) : null;

        BigInteger workingReleaseId = findId(RELEASE, RELEASE.RELEASE_ID,
                RELEASE.LIBRARY_ID.eq(toULong(context.libraryId))
                        .and(RELEASE.RELEASE_NUM.eq("Working")));

        if (prevReleaseId != null) {
            BigInteger prevNextId = findId(RELEASE, RELEASE.NEXT_RELEASE_ID, RELEASE.RELEASE_ID.eq(toULong(prevReleaseId)));
            if (prevNextId != null && !prevNextId.equals(context.releaseId)
                    && (workingReleaseId == null || !prevNextId.equals(workingReleaseId))) {
                throw new IllegalArgumentException("Release chain conflict on dependency '" + dependsOn.releaseNum() + "'.");
            }
            updateRowById(RELEASE, RELEASE.RELEASE_ID, prevReleaseId,
                    mapOf(RELEASE.NEXT_RELEASE_ID, toULong(context.releaseId)));
        }

        LinkedHashMap<Field<?>, Object> releaseValues = new LinkedHashMap<>();
        releaseValues.put(RELEASE.PREV_RELEASE_ID, nullableULong(prevReleaseId));
        if (workingReleaseId != null && !workingReleaseId.equals(context.releaseId)) {
            releaseValues.put(RELEASE.NEXT_RELEASE_ID, toULong(workingReleaseId));
        }
        updateRowById(RELEASE, RELEASE.RELEASE_ID, context.releaseId, releaseValues);

        if (workingReleaseId != null && !workingReleaseId.equals(context.releaseId)) {
            BigInteger workingPrevId = findId(RELEASE, RELEASE.PREV_RELEASE_ID, RELEASE.RELEASE_ID.eq(toULong(workingReleaseId)));
            if (workingPrevId != null && prevReleaseId != null && !workingPrevId.equals(prevReleaseId) && !workingPrevId.equals(context.releaseId)) {
                throw new IllegalArgumentException("Working release already points to a different published release.");
            }
            updateRowById(RELEASE, RELEASE.RELEASE_ID, workingReleaseId,
                    mapOf(RELEASE.PREV_RELEASE_ID, toULong(context.releaseId)));
        }
    }

    private void importTags(ImportContext context) {
        for (Map<String, Object> row : context.rows("tag")) {
            String name = string(row, "name");
            BigInteger id = findId(TAG, TAG.TAG_ID, TAG.NAME.eq(name));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(TAG, TAG.TAG_ID, internalId(row), mapOf(
                        TAG.NAME, name,
                        TAG.DESCRIPTION, string(row, "description"),
                        TAG.TEXT_COLOR, string(row, "text_color"),
                        TAG.BACKGROUND_COLOR, string(row, "background_color"),
                        TAG.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        TAG.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        TAG.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        TAG.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByName("tag").put(name, id);
        }
    }

    private void importModuleSets(ImportContext context) {
        for (Map<String, Object> row : context.rows("module_set")) {
            String guid = string(row, "guid");
            BigInteger id = findId(MODULE_SET, MODULE_SET.MODULE_SET_ID, MODULE_SET.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(MODULE_SET, MODULE_SET.MODULE_SET_ID, internalId(row), mapOf(
                        MODULE_SET.GUID, guid,
                        MODULE_SET.NAME, string(row, "name"),
                        MODULE_SET.DESCRIPTION, string(row, "description"),
                        MODULE_SET.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        MODULE_SET.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        MODULE_SET.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        MODULE_SET.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("module_set").put(guid, id);
        }
    }

    private void importXbts(ImportContext context) {
        for (Map<String, Object> row : context.rows("xbt")) {
            String guid = string(row, "guid");
            BigInteger id = findId(XBT, XBT.XBT_ID, XBT.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(XBT, XBT.XBT_ID, internalId(row), mapOf(
                        XBT.GUID, guid,
                        XBT.NAME, string(row, "name"),
                        XBT.BUILTIN_TYPE, string(row, "builtin_type"),
                        XBT.JBT_DRAFT05_MAP, string(row, "jbt_draft05_map"),
                        XBT.JBT_202012_MAP, string(row, "jbt_202012_map"),
                        XBT.OPENAPI30_MAP, string(row, "openapi30_map"),
                        XBT.OPENAPI31_MAP, string(row, "openapi31_map"),
                        XBT.AVRO_MAP, string(row, "avro_map"),
                        XBT.SCHEMA_DEFINITION, string(row, "schema_definition"),
                        XBT.REVISION_DOC, string(row, "revision_doc"),
                        XBT.STATE, string(row, "state"),
                        XBT.IS_DEPRECATED, bit(row, "is_deprecated"),
                        XBT.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        XBT.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        XBT.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        XBT.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        XBT.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("xbt").put(guid, id);
        }
    }

    private void importDts(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt")) {
            String guid = string(row, "guid");
            BigInteger id = findId(DT, DT.DT_ID, DT.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(DT, DT.DT_ID, internalId(row), mapOf(
                        DT.GUID, guid,
                        DT.DATA_TYPE_TERM, string(row, "data_type_term"),
                        DT.QUALIFIER, string(row, "qualifier"),
                        DT.REPRESENTATION_TERM, string(row, "representation_term"),
                        DT.SIX_DIGIT_ID, string(row, "six_digit_id"),
                        DT.DEFINITION, string(row, "definition"),
                        DT.DEFINITION_SOURCE, string(row, "definition_source"),
                        DT.CONTENT_COMPONENT_DEFINITION, string(row, "content_component_definition"),
                        DT.COMMONLY_USED, bit(row, "commonly_used"),
                        DT.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        DT.STATE, string(row, "state"),
                        DT.IS_DEPRECATED, bit(row, "is_deprecated"),
                        DT.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        DT.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        DT.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        DT.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        DT.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("dt").put(guid, id);
        }
    }

    private void importAccs(ImportContext context) {
        for (Map<String, Object> row : context.rows("acc")) {
            String guid = string(row, "guid");
            BigInteger id = findId(ACC, ACC.ACC_ID, ACC.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(ACC, ACC.ACC_ID, internalId(row), mapOf(
                        ACC.GUID, guid,
                        ACC.TYPE, string(row, "type"),
                        ACC.OBJECT_CLASS_TERM, string(row, "object_class_term"),
                        ACC.DEFINITION, string(row, "definition"),
                        ACC.DEFINITION_SOURCE, string(row, "definition_source"),
                        ACC.OBJECT_CLASS_QUALIFIER, string(row, "object_class_qualifier"),
                        ACC.OAGIS_COMPONENT_TYPE, integer(row, "oagis_component_type"),
                        ACC.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        ACC.STATE, string(row, "state"),
                        ACC.IS_DEPRECATED, bit(row, "is_deprecated"),
                        ACC.IS_ABSTRACT, bit(row, "is_abstract"),
                        ACC.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        ACC.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        ACC.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        ACC.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        ACC.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("acc").put(guid, id);
        }
    }

    private void importAsccps(ImportContext context) {
        for (Map<String, Object> row : context.rows("asccp")) {
            String guid = string(row, "guid");
            BigInteger id = findId(ASCCP, ASCCP.ASCCP_ID, ASCCP.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(ASCCP, ASCCP.ASCCP_ID, internalId(row), mapOf(
                        ASCCP.GUID, guid,
                        ASCCP.TYPE, string(row, "type"),
                        ASCCP.PROPERTY_TERM, string(row, "property_term"),
                        ASCCP.DEFINITION, string(row, "definition"),
                        ASCCP.DEFINITION_SOURCE, string(row, "definition_source"),
                        ASCCP.ROLE_OF_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "role_of_acc_guid"))),
                        ASCCP.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        ASCCP.REUSABLE_INDICATOR, bit(row, "reusable_indicator"),
                        ASCCP.IS_DEPRECATED, bit(row, "is_deprecated"),
                        ASCCP.IS_NILLABLE, bit(row, "is_nillable"),
                        ASCCP.STATE, string(row, "state"),
                        ASCCP.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        ASCCP.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        ASCCP.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        ASCCP.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        ASCCP.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("asccp").put(guid, id);
        }
    }

    private void importBccps(ImportContext context) {
        for (Map<String, Object> row : context.rows("bccp")) {
            String guid = string(row, "guid");
            BigInteger id = findId(BCCP, BCCP.BCCP_ID, BCCP.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(BCCP, BCCP.BCCP_ID, internalId(row), mapOf(
                        BCCP.GUID, guid,
                        BCCP.PROPERTY_TERM, string(row, "property_term"),
                        BCCP.REPRESENTATION_TERM, string(row, "representation_term"),
                        BCCP.BDT_ID, nullableULong(resolveGuidId(context, "dt", string(row, "bdt_guid"))),
                        BCCP.DEFINITION, string(row, "definition"),
                        BCCP.DEFINITION_SOURCE, string(row, "definition_source"),
                        BCCP.DEFAULT_VALUE, string(row, "default_value"),
                        BCCP.FIXED_VALUE, string(row, "fixed_value"),
                        BCCP.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        BCCP.STATE, string(row, "state"),
                        BCCP.IS_DEPRECATED, bit(row, "is_deprecated"),
                        BCCP.IS_NILLABLE, bit(row, "is_nillable"),
                        BCCP.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        BCCP.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        BCCP.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        BCCP.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        BCCP.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("bccp").put(guid, id);
        }
    }

    private void importAsccs(ImportContext context) {
        for (Map<String, Object> row : context.rows("ascc")) {
            String guid = string(row, "guid");
            BigInteger id = findId(ASCC, ASCC.ASCC_ID, ASCC.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(ASCC, ASCC.ASCC_ID, internalId(row), mapOf(
                        ASCC.GUID, guid,
                        ASCC.CARDINALITY_MIN, integer(row, "cardinality_min"),
                        ASCC.CARDINALITY_MAX, integer(row, "cardinality_max"),
                        ASCC.SEQ_KEY, integer(row, "seq_key"),
                        ASCC.FROM_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "from_acc_guid"))),
                        ASCC.TO_ASCCP_ID, nullableULong(resolveGuidId(context, "asccp", string(row, "to_asccp_guid"))),
                        ASCC.DEFINITION, string(row, "definition"),
                        ASCC.DEFINITION_SOURCE, string(row, "definition_source"),
                        ASCC.IS_DEPRECATED, bit(row, "is_deprecated"),
                        ASCC.STATE, string(row, "state"),
                        ASCC.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        ASCC.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        ASCC.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        ASCC.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        ASCC.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("ascc").put(guid, id);
        }
    }

    private void importBccs(ImportContext context) {
        for (Map<String, Object> row : context.rows("bcc")) {
            String guid = string(row, "guid");
            BigInteger id = findId(BCC, BCC.BCC_ID, BCC.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(BCC, BCC.BCC_ID, internalId(row), mapOf(
                        BCC.GUID, guid,
                        BCC.CARDINALITY_MIN, integer(row, "cardinality_min"),
                        BCC.CARDINALITY_MAX, integer(row, "cardinality_max"),
                        BCC.SEQ_KEY, integer(row, "seq_key"),
                        BCC.ENTITY_TYPE, string(row, "entity_type"),
                        BCC.FROM_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "from_acc_guid"))),
                        BCC.TO_BCCP_ID, nullableULong(resolveGuidId(context, "bccp", string(row, "to_bccp_guid"))),
                        BCC.DEFINITION, string(row, "definition"),
                        BCC.DEFINITION_SOURCE, string(row, "definition_source"),
                        BCC.IS_DEPRECATED, bit(row, "is_deprecated"),
                        BCC.IS_NILLABLE, bit(row, "is_nillable"),
                        BCC.DEFAULT_VALUE, string(row, "default_value"),
                        BCC.FIXED_VALUE, string(row, "fixed_value"),
                        BCC.STATE, string(row, "state"),
                        BCC.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        BCC.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        BCC.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        BCC.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        BCC.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("bcc").put(guid, id);
        }
    }

    private void importDtScs(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt_sc")) {
            String guid = string(row, "guid");
            BigInteger id = findId(DT_SC, DT_SC.DT_SC_ID, DT_SC.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(DT_SC, DT_SC.DT_SC_ID, internalId(row), mapOf(
                        DT_SC.GUID, guid,
                        DT_SC.CARDINALITY_MIN, integer(row, "cardinality_min"),
                        DT_SC.CARDINALITY_MAX, integer(row, "cardinality_max"),
                        DT_SC.OBJECT_CLASS_TERM, string(row, "object_class_term"),
                        DT_SC.PROPERTY_TERM, string(row, "property_term"),
                        DT_SC.REPRESENTATION_TERM, string(row, "representation_term"),
                        DT_SC.OWNER_DT_ID, nullableULong(resolveGuidId(context, "dt", string(row, "owner_dt_guid"))),
                        DT_SC.DEFINITION, string(row, "definition"),
                        DT_SC.DEFINITION_SOURCE, string(row, "definition_source"),
                        DT_SC.DEFAULT_VALUE, string(row, "default_value"),
                        DT_SC.FIXED_VALUE, string(row, "fixed_value"),
                        DT_SC.IS_DEPRECATED, bit(row, "is_deprecated"),
                        DT_SC.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        DT_SC.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        DT_SC.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        DT_SC.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        DT_SC.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("dt_sc").put(guid, id);
        }
    }

    private void importCodeLists(ImportContext context) {
        for (Map<String, Object> row : context.rows("code_list")) {
            String guid = string(row, "guid");
            BigInteger id = findId(CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(CODE_LIST, CODE_LIST.CODE_LIST_ID, internalId(row), mapOf(
                        CODE_LIST.GUID, guid,
                        CODE_LIST.ENUM_TYPE_GUID, string(row, "enum_type_guid"),
                        CODE_LIST.NAME, string(row, "name"),
                        CODE_LIST.LIST_ID, string(row, "list_id"),
                        CODE_LIST.VERSION_ID, string(row, "version_id"),
                        CODE_LIST.DEFINITION, string(row, "definition"),
                        CODE_LIST.DEFINITION_SOURCE, string(row, "definition_source"),
                        CODE_LIST.REMARK, string(row, "remark"),
                        CODE_LIST.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        CODE_LIST.STATE, string(row, "state"),
                        CODE_LIST.IS_DEPRECATED, bit(row, "is_deprecated"),
                        CODE_LIST.EXTENSIBLE_INDICATOR, bit(row, "extensible_indicator"),
                        CODE_LIST.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        CODE_LIST.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        CODE_LIST.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        CODE_LIST.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        CODE_LIST.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("code_list").put(guid, id);
        }
    }

    private void importCodeListValues(ImportContext context) {
        for (Map<String, Object> row : context.rows("code_list_value")) {
            String guid = string(row, "guid");
            BigInteger id = findId(CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, CODE_LIST_VALUE.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, internalId(row), mapOf(
                        CODE_LIST_VALUE.GUID, guid,
                        CODE_LIST_VALUE.CODE_LIST_ID, nullableULong(resolveGuidId(context, "code_list", string(row, "code_list_guid"))),
                        CODE_LIST_VALUE.VALUE, string(row, "value"),
                        CODE_LIST_VALUE.MEANING, string(row, "meaning"),
                        CODE_LIST_VALUE.DEFINITION, string(row, "definition"),
                        CODE_LIST_VALUE.DEFINITION_SOURCE, string(row, "definition_source"),
                        CODE_LIST_VALUE.IS_DEPRECATED, bit(row, "is_deprecated"),
                        CODE_LIST_VALUE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        CODE_LIST_VALUE.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        CODE_LIST_VALUE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        CODE_LIST_VALUE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        CODE_LIST_VALUE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("code_list_value").put(guid, id);
        }
    }

    private void importAgencyIdLists(ImportContext context) {
        for (Map<String, Object> row : context.rows("agency_id_list")) {
            String guid = string(row, "guid");
            BigInteger id = findId(AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, internalId(row), mapOf(
                        AGENCY_ID_LIST.GUID, guid,
                        AGENCY_ID_LIST.ENUM_TYPE_GUID, string(row, "enum_type_guid"),
                        AGENCY_ID_LIST.NAME, string(row, "name"),
                        AGENCY_ID_LIST.LIST_ID, string(row, "list_id"),
                        AGENCY_ID_LIST.VERSION_ID, string(row, "version_id"),
                        AGENCY_ID_LIST.DEFINITION, string(row, "definition"),
                        AGENCY_ID_LIST.DEFINITION_SOURCE, string(row, "definition_source"),
                        AGENCY_ID_LIST.REMARK, string(row, "remark"),
                        AGENCY_ID_LIST.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        AGENCY_ID_LIST.STATE, string(row, "state"),
                        AGENCY_ID_LIST.IS_DEPRECATED, bit(row, "is_deprecated"),
                        AGENCY_ID_LIST.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        AGENCY_ID_LIST.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        AGENCY_ID_LIST.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        AGENCY_ID_LIST.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        AGENCY_ID_LIST.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("agency_id_list").put(guid, id);
        }
    }

    private void importAgencyIdListValues(ImportContext context) {
        for (Map<String, Object> row : context.rows("agency_id_list_value")) {
            String guid = string(row, "guid");
            BigInteger id = findId(AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, AGENCY_ID_LIST_VALUE.GUID.eq(guid));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, internalId(row), mapOf(
                        AGENCY_ID_LIST_VALUE.GUID, guid,
                        AGENCY_ID_LIST_VALUE.OWNER_LIST_ID, nullableULong(resolveGuidId(context, "agency_id_list", string(row, "agency_id_list_guid"))),
                        AGENCY_ID_LIST_VALUE.VALUE, string(row, "value"),
                        AGENCY_ID_LIST_VALUE.NAME, string(row, "name"),
                        AGENCY_ID_LIST_VALUE.DEFINITION, string(row, "definition"),
                        AGENCY_ID_LIST_VALUE.DEFINITION_SOURCE, string(row, "definition_source"),
                        AGENCY_ID_LIST_VALUE.IS_DEPRECATED, bit(row, "is_deprecated"),
                        AGENCY_ID_LIST_VALUE.IS_DEVELOPER_DEFAULT, bit(row, "is_developer_default"),
                        AGENCY_ID_LIST_VALUE.IS_USER_DEFAULT, bit(row, "is_user_default"),
                        AGENCY_ID_LIST_VALUE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        AGENCY_ID_LIST_VALUE.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        AGENCY_ID_LIST_VALUE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        AGENCY_ID_LIST_VALUE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        AGENCY_ID_LIST_VALUE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByGuid("agency_id_list_value").put(guid, id);
        }
    }

    private void importBlobContents(ImportContext context) {
        for (Map<String, Object> row : context.rows("blob_content")) {
            BigInteger hint = internalId(row);
            BigInteger id = (hint != null) ? findId(BLOB_CONTENT, BLOB_CONTENT.BLOB_CONTENT_ID, BLOB_CONTENT.BLOB_CONTENT_ID.eq(toULong(hint))) : null;
            if (id == null) {
                id = insertRow(BLOB_CONTENT, BLOB_CONTENT.BLOB_CONTENT_ID, hint, mapOf(
                        BLOB_CONTENT.CONTENT, bytes(row, "content")
                ));
            }
            if (hint != null) {
                context.idByInternal("blob_content").put(hint, id);
            }
        }
    }

    private void importModuleSetReleases(ImportContext context) {
        for (Map<String, Object> row : context.rows("module_set_release")) {
            BigInteger moduleSetId = resolveGuidId(context, "module_set", string(row, "module_set_guid"));
            String key = context.moduleSetReleaseKey(string(row, "module_set_guid"), string(row, "name"));
            BigInteger id = findId(MODULE_SET_RELEASE, MODULE_SET_RELEASE.MODULE_SET_RELEASE_ID,
                    MODULE_SET_RELEASE.MODULE_SET_ID.eq(toULong(moduleSetId))
                            .and(MODULE_SET_RELEASE.RELEASE_ID.eq(toULong(context.releaseId)))
                            .and(MODULE_SET_RELEASE.NAME.eq(string(row, "name"))));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(MODULE_SET_RELEASE, MODULE_SET_RELEASE.MODULE_SET_RELEASE_ID, internalId(row), mapOf(
                        MODULE_SET_RELEASE.MODULE_SET_ID, toULong(moduleSetId),
                        MODULE_SET_RELEASE.RELEASE_ID, toULong(context.releaseId),
                        MODULE_SET_RELEASE.NAME, string(row, "name"),
                        MODULE_SET_RELEASE.DESCRIPTION, string(row, "description"),
                        MODULE_SET_RELEASE.IS_DEFAULT, bit(row, "is_default"),
                        MODULE_SET_RELEASE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        MODULE_SET_RELEASE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        MODULE_SET_RELEASE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        MODULE_SET_RELEASE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByKey("module_set_release").put(key, id);
        }
    }

    private void importModules(ImportContext context) {
        for (Map<String, Object> row : context.rows("module")) {
            BigInteger moduleSetId = resolveGuidId(context, "module_set", string(row, "module_set_guid"));
            String key = context.moduleKey(string(row, "module_set_guid"), string(row, "path"));
            BigInteger id = findId(MODULE, MODULE.MODULE_ID,
                    MODULE.MODULE_SET_ID.eq(toULong(moduleSetId))
                            .and(MODULE.PATH.eq(string(row, "path"))));
            if (id == null) {
                LocalDateTime now = LocalDateTime.now();
                id = insertRow(MODULE, MODULE.MODULE_ID, internalId(row), mapOf(
                        MODULE.MODULE_SET_ID, toULong(moduleSetId),
                        MODULE.TYPE, string(row, "type"),
                        MODULE.PATH, string(row, "path"),
                        MODULE.NAME, string(row, "name"),
                        MODULE.NAMESPACE_ID, nullableULong(resolveNamespaceId(context, string(row, "namespace_uri"))),
                        MODULE.VERSION_NUM, string(row, "version_num"),
                        MODULE.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        MODULE.OWNER_USER_ID, toULong(resolveUserId(context, string(row, "owner_login_id"))),
                        MODULE.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        MODULE.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        MODULE.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
            context.idByKey("module").put(key, id);
        }
    }

    private void updateXbtLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("xbt")) {
            addGuidMappedSelfLinkUpdate(queries, context, "xbt", string(row, "guid"), XBT, XBT.XBT_ID, mapOf(
                    XBT.SUBTYPE_OF_XBT_ID, nullableULong(resolveGuidId(context, "xbt", string(row, "subtype_of_xbt_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateDtLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("dt")) {
            addGuidMappedSelfLinkUpdate(queries, context, "dt", string(row, "guid"), DT, DT.DT_ID, mapOf(
                    DT.BASED_DT_ID, nullableULong(resolveGuidId(context, "dt", string(row, "based_dt_guid"))),
                    DT.REPLACEMENT_DT_ID, nullableULong(resolveGuidId(context, "dt", string(row, "replacement_dt_guid"))),
                    DT.PREV_DT_ID, nullableULong(resolveGuidId(context, "dt", string(row, "prev_dt_guid"))),
                    DT.NEXT_DT_ID, nullableULong(resolveGuidId(context, "dt", string(row, "next_dt_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAccLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("acc")) {
            addGuidMappedSelfLinkUpdate(queries, context, "acc", string(row, "guid"), ACC, ACC.ACC_ID, mapOf(
                    ACC.BASED_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "based_acc_guid"))),
                    ACC.REPLACEMENT_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "replacement_acc_guid"))),
                    ACC.PREV_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "prev_acc_guid"))),
                    ACC.NEXT_ACC_ID, nullableULong(resolveGuidId(context, "acc", string(row, "next_acc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAsccpLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("asccp")) {
            addGuidMappedSelfLinkUpdate(queries, context, "asccp", string(row, "guid"), ASCCP, ASCCP.ASCCP_ID, mapOf(
                    ASCCP.REPLACEMENT_ASCCP_ID, nullableULong(resolveGuidId(context, "asccp", string(row, "replacement_asccp_guid"))),
                    ASCCP.PREV_ASCCP_ID, nullableULong(resolveGuidId(context, "asccp", string(row, "prev_asccp_guid"))),
                    ASCCP.NEXT_ASCCP_ID, nullableULong(resolveGuidId(context, "asccp", string(row, "next_asccp_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateBccpLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("bccp")) {
            addGuidMappedSelfLinkUpdate(queries, context, "bccp", string(row, "guid"), BCCP, BCCP.BCCP_ID, mapOf(
                    BCCP.REPLACEMENT_BCCP_ID, nullableULong(resolveGuidId(context, "bccp", string(row, "replacement_bccp_guid"))),
                    BCCP.PREV_BCCP_ID, nullableULong(resolveGuidId(context, "bccp", string(row, "prev_bccp_guid"))),
                    BCCP.NEXT_BCCP_ID, nullableULong(resolveGuidId(context, "bccp", string(row, "next_bccp_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAsccLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("ascc")) {
            addGuidMappedSelfLinkUpdate(queries, context, "ascc", string(row, "guid"), ASCC, ASCC.ASCC_ID, mapOf(
                    ASCC.REPLACEMENT_ASCC_ID, nullableULong(resolveGuidId(context, "ascc", string(row, "replacement_ascc_guid"))),
                    ASCC.PREV_ASCC_ID, nullableULong(resolveGuidId(context, "ascc", string(row, "prev_ascc_guid"))),
                    ASCC.NEXT_ASCC_ID, nullableULong(resolveGuidId(context, "ascc", string(row, "next_ascc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateBccLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("bcc")) {
            addGuidMappedSelfLinkUpdate(queries, context, "bcc", string(row, "guid"), BCC, BCC.BCC_ID, mapOf(
                    BCC.REPLACEMENT_BCC_ID, nullableULong(resolveGuidId(context, "bcc", string(row, "replacement_bcc_guid"))),
                    BCC.PREV_BCC_ID, nullableULong(resolveGuidId(context, "bcc", string(row, "prev_bcc_guid"))),
                    BCC.NEXT_BCC_ID, nullableULong(resolveGuidId(context, "bcc", string(row, "next_bcc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateDtScLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("dt_sc")) {
            addGuidMappedSelfLinkUpdate(queries, context, "dt_sc", string(row, "guid"), DT_SC, DT_SC.DT_SC_ID, mapOf(
                    DT_SC.BASED_DT_SC_ID, nullableULong(resolveGuidId(context, "dt_sc", string(row, "based_dt_sc_guid"))),
                    DT_SC.REPLACEMENT_DT_SC_ID, nullableULong(resolveGuidId(context, "dt_sc", string(row, "replacement_dt_sc_guid"))),
                    DT_SC.PREV_DT_SC_ID, nullableULong(resolveGuidId(context, "dt_sc", string(row, "prev_dt_sc_guid"))),
                    DT_SC.NEXT_DT_SC_ID, nullableULong(resolveGuidId(context, "dt_sc", string(row, "next_dt_sc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateCodeListLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("code_list")) {
            addGuidMappedSelfLinkUpdate(queries, context, "code_list", string(row, "guid"), CODE_LIST, CODE_LIST.CODE_LIST_ID, mapOf(
                    CODE_LIST.BASED_CODE_LIST_ID, nullableULong(resolveGuidId(context, "code_list", string(row, "based_code_list_guid"))),
                    CODE_LIST.REPLACEMENT_CODE_LIST_ID, nullableULong(resolveGuidId(context, "code_list", string(row, "replacement_code_list_guid"))),
                    CODE_LIST.PREV_CODE_LIST_ID, nullableULong(resolveGuidId(context, "code_list", string(row, "prev_code_list_guid"))),
                    CODE_LIST.NEXT_CODE_LIST_ID, nullableULong(resolveGuidId(context, "code_list", string(row, "next_code_list_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateCodeListValueLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("code_list_value")) {
            addGuidMappedSelfLinkUpdate(queries, context, "code_list_value", string(row, "guid"), CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, mapOf(
                    CODE_LIST_VALUE.BASED_CODE_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "code_list_value", string(row, "based_code_list_value_guid"))),
                    CODE_LIST_VALUE.REPLACEMENT_CODE_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "code_list_value", string(row, "replacement_code_list_value_guid"))),
                    CODE_LIST_VALUE.PREV_CODE_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "code_list_value", string(row, "prev_code_list_value_guid"))),
                    CODE_LIST_VALUE.NEXT_CODE_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "code_list_value", string(row, "next_code_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAgencyIdListLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("agency_id_list")) {
            addGuidMappedSelfLinkUpdate(queries, context, "agency_id_list", string(row, "guid"), AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, mapOf(
                    AGENCY_ID_LIST.BASED_AGENCY_ID_LIST_ID, nullableULong(resolveGuidId(context, "agency_id_list", string(row, "based_agency_id_list_guid"))),
                    AGENCY_ID_LIST.REPLACEMENT_AGENCY_ID_LIST_ID, nullableULong(resolveGuidId(context, "agency_id_list", string(row, "replacement_agency_id_list_guid"))),
                    AGENCY_ID_LIST.PREV_AGENCY_ID_LIST_ID, nullableULong(resolveGuidId(context, "agency_id_list", string(row, "prev_agency_id_list_guid"))),
                    AGENCY_ID_LIST.NEXT_AGENCY_ID_LIST_ID, nullableULong(resolveGuidId(context, "agency_id_list", string(row, "next_agency_id_list_guid"))),
                    AGENCY_ID_LIST.AGENCY_ID_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "agency_id_list_value", string(row, "agency_id_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAgencyIdListValueLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("agency_id_list_value")) {
            addGuidMappedSelfLinkUpdate(queries, context, "agency_id_list_value", string(row, "guid"), AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, mapOf(
                    AGENCY_ID_LIST_VALUE.BASED_AGENCY_ID_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "agency_id_list_value", string(row, "based_agency_id_list_value_guid"))),
                    AGENCY_ID_LIST_VALUE.REPLACEMENT_AGENCY_ID_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "agency_id_list_value", string(row, "replacement_agency_id_list_value_guid"))),
                    AGENCY_ID_LIST_VALUE.PREV_AGENCY_ID_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "agency_id_list_value", string(row, "prev_agency_id_list_value_guid"))),
                    AGENCY_ID_LIST_VALUE.NEXT_AGENCY_ID_LIST_VALUE_ID, nullableULong(resolveGuidId(context, "agency_id_list_value", string(row, "next_agency_id_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateModuleLinks(ImportContext context) {
        for (Map<String, Object> row : context.rows("module")) {
            BigInteger moduleId = context.idByKey("module").get(context.moduleKey(string(row, "module_set_guid"), string(row, "path")));
            if (moduleId == null) {
                continue;
            }
            updateRowById(MODULE, MODULE.MODULE_ID, moduleId, mapOf(
                    MODULE.PARENT_MODULE_ID, nullableULong(resolveModuleId(context, string(row, "module_set_guid"), string(row, "parent_module_path")))
            ));
        }
    }

    private void importXbtManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("xbt_manifest")) {
            String guid = string(row, "xbt_guid");
            BigInteger xbtId = resolveGuidId(context, "xbt", guid);
            BigInteger id = findId(XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID,
                    XBT_MANIFEST.RELEASE_ID.eq(toULong(context.releaseId))
                            .and(XBT_MANIFEST.XBT_ID.eq(toULong(xbtId))));
            if (id == null) {
                id = insertRow(XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, internalId(row), mapOf(
                        XBT_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        XBT_MANIFEST.XBT_ID, toULong(xbtId),
                        XBT_MANIFEST.CDT_PRI_ID, nullableULong(resolveCdtPriId(string(row, "cdt_pri_name"))),
                        XBT_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("xbt_manifest").put(guid, id);
        }
    }

    private void importDtManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt_manifest")) {
            String guid = string(row, "dt_guid");
            BigInteger rawId = resolveGuidId(context, "dt", guid);
            BigInteger id = findManifestId(DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.RELEASE_ID, DT_MANIFEST.DT_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, internalId(row), mapOf(
                        DT_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        DT_MANIFEST.DT_ID, toULong(rawId),
                        DT_MANIFEST.DEN, string(row, "den"),
                        DT_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("dt_manifest").put(guid, id);
        }
    }

    private void importAccManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("acc_manifest")) {
            String guid = string(row, "acc_guid");
            BigInteger rawId = resolveGuidId(context, "acc", guid);
            BigInteger id = findManifestId(ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.RELEASE_ID, ACC_MANIFEST.ACC_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, internalId(row), mapOf(
                        ACC_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        ACC_MANIFEST.ACC_ID, toULong(rawId),
                        ACC_MANIFEST.DEN, string(row, "den"),
                        ACC_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("acc_manifest").put(guid, id);
        }
    }

    private void importAsccpManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("asccp_manifest")) {
            String guid = string(row, "asccp_guid");
            BigInteger rawId = resolveGuidId(context, "asccp", guid);
            BigInteger id = findManifestId(ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, ASCCP_MANIFEST.RELEASE_ID, ASCCP_MANIFEST.ASCCP_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, internalId(row), mapOf(
                        ASCCP_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        ASCCP_MANIFEST.ASCCP_ID, toULong(rawId),
                        ASCCP_MANIFEST.ROLE_OF_ACC_MANIFEST_ID, nullableULong(resolveGuidId(context, "acc_manifest", string(row, "role_of_acc_guid"))),
                        ASCCP_MANIFEST.DEN, string(row, "den"),
                        ASCCP_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("asccp_manifest").put(guid, id);
        }
    }

    private void importBccpManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("bccp_manifest")) {
            String guid = string(row, "bccp_guid");
            BigInteger rawId = resolveGuidId(context, "bccp", guid);
            BigInteger id = findManifestId(BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, BCCP_MANIFEST.RELEASE_ID, BCCP_MANIFEST.BCCP_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, internalId(row), mapOf(
                        BCCP_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        BCCP_MANIFEST.BCCP_ID, toULong(rawId),
                        BCCP_MANIFEST.BDT_MANIFEST_ID, nullableULong(resolveGuidId(context, "dt_manifest", string(row, "bdt_guid"))),
                        BCCP_MANIFEST.DEN, string(row, "den"),
                        BCCP_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("bccp_manifest").put(guid, id);
        }
    }

    private void importDtScManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt_sc_manifest")) {
            String guid = string(row, "dt_sc_guid");
            BigInteger rawId = resolveGuidId(context, "dt_sc", guid);
            BigInteger id = findManifestId(DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, DT_SC_MANIFEST.RELEASE_ID, DT_SC_MANIFEST.DT_SC_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, internalId(row), mapOf(
                        DT_SC_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        DT_SC_MANIFEST.DT_SC_ID, toULong(rawId),
                        DT_SC_MANIFEST.OWNER_DT_MANIFEST_ID, nullableULong(resolveGuidId(context, "dt_manifest", string(row, "owner_dt_guid"))),
                        DT_SC_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("dt_sc_manifest").put(guid, id);
        }
    }

    private void importAsccManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("ascc_manifest")) {
            String guid = string(row, "ascc_guid");
            BigInteger rawId = resolveGuidId(context, "ascc", guid);
            BigInteger id = findManifestId(ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, ASCC_MANIFEST.RELEASE_ID, ASCC_MANIFEST.ASCC_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, internalId(row), mapOf(
                        ASCC_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        ASCC_MANIFEST.ASCC_ID, toULong(rawId),
                        ASCC_MANIFEST.FROM_ACC_MANIFEST_ID, nullableULong(resolveGuidId(context, "acc_manifest", string(row, "from_acc_guid"))),
                        ASCC_MANIFEST.TO_ASCCP_MANIFEST_ID, nullableULong(resolveGuidId(context, "asccp_manifest", string(row, "to_asccp_guid"))),
                        ASCC_MANIFEST.DEN, string(row, "den"),
                        ASCC_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("ascc_manifest").put(guid, id);
        }
    }

    private void importBccManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("bcc_manifest")) {
            String guid = string(row, "bcc_guid");
            BigInteger rawId = resolveGuidId(context, "bcc", guid);
            BigInteger id = findManifestId(BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, BCC_MANIFEST.RELEASE_ID, BCC_MANIFEST.BCC_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, internalId(row), mapOf(
                        BCC_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        BCC_MANIFEST.BCC_ID, toULong(rawId),
                        BCC_MANIFEST.FROM_ACC_MANIFEST_ID, nullableULong(resolveGuidId(context, "acc_manifest", string(row, "from_acc_guid"))),
                        BCC_MANIFEST.TO_BCCP_MANIFEST_ID, nullableULong(resolveGuidId(context, "bccp_manifest", string(row, "to_bccp_guid"))),
                        BCC_MANIFEST.DEN, string(row, "den"),
                        BCC_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("bcc_manifest").put(guid, id);
        }
    }

    private void importCodeListManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("code_list_manifest")) {
            String guid = string(row, "code_list_guid");
            BigInteger rawId = resolveGuidId(context, "code_list", guid);
            BigInteger id = findManifestId(CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.RELEASE_ID, CODE_LIST_MANIFEST.CODE_LIST_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, internalId(row), mapOf(
                        CODE_LIST_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        CODE_LIST_MANIFEST.CODE_LIST_ID, toULong(rawId),
                        CODE_LIST_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("code_list_manifest").put(guid, id);
        }
    }

    private void importCodeListValueManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("code_list_value_manifest")) {
            String guid = string(row, "code_list_value_guid");
            BigInteger rawId = resolveGuidId(context, "code_list_value", guid);
            BigInteger id = findManifestId(CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID,
                    CODE_LIST_VALUE_MANIFEST.RELEASE_ID, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, internalId(row), mapOf(
                        CODE_LIST_VALUE_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID, toULong(rawId),
                        CODE_LIST_VALUE_MANIFEST.CODE_LIST_MANIFEST_ID, nullableULong(resolveGuidId(context, "code_list_manifest", string(row, "code_list_guid"))),
                        CODE_LIST_VALUE_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("code_list_value_manifest").put(guid, id);
        }
    }

    private void importAgencyIdListManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("agency_id_list_manifest")) {
            String guid = string(row, "agency_id_list_guid");
            BigInteger rawId = resolveGuidId(context, "agency_id_list", guid);
            BigInteger id = findManifestId(AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID,
                    AGENCY_ID_LIST_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, internalId(row), mapOf(
                        AGENCY_ID_LIST_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID, toULong(rawId),
                        AGENCY_ID_LIST_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("agency_id_list_manifest").put(guid, id);
        }
    }

    private void importAgencyIdListValueManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("agency_id_list_value_manifest")) {
            String guid = string(row, "agency_id_list_value_guid");
            BigInteger rawId = resolveGuidId(context, "agency_id_list_value", guid);
            BigInteger id = findManifestId(AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID,
                    AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID, context.releaseId, rawId);
            if (id == null) {
                id = insertRow(AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, internalId(row), mapOf(
                        AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID, toULong(rawId),
                        AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, nullableULong(resolveGuidId(context, "agency_id_list_manifest", string(row, "agency_id_list_guid"))),
                        AGENCY_ID_LIST_VALUE_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            context.idByGuid("agency_id_list_value_manifest").put(guid, id);
        }
    }

    private void importBlobContentManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("blob_content_manifest")) {
            BigInteger blobContentId = context.idByInternal("blob_content").get(number(row, "blob_content_internal_id"));
            BigInteger hint = internalId(row);
            BigInteger id = null;
            if (hint != null) {
                id = findId(BLOB_CONTENT_MANIFEST, BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID,
                        BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID.eq(toULong(hint)));
            }
            if (id == null && blobContentId != null) {
                id = findId(BLOB_CONTENT_MANIFEST, BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID,
                        BLOB_CONTENT_MANIFEST.RELEASE_ID.eq(toULong(context.releaseId))
                                .and(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_ID.eq(toULong(blobContentId))));
            }
            if (id == null) {
                id = insertRow(BLOB_CONTENT_MANIFEST, BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID, hint, mapOf(
                        BLOB_CONTENT_MANIFEST.BLOB_CONTENT_ID, toULong(blobContentId),
                        BLOB_CONTENT_MANIFEST.RELEASE_ID, toULong(context.releaseId),
                        BLOB_CONTENT_MANIFEST.CONFLICT, bit(row, "conflict")
                ));
            }
            if (hint != null) {
                context.idByInternal("blob_content_manifest").put(hint, id);
            }
        }
    }

    private void updateXbtManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("xbt_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "xbt_manifest", string(row, "xbt_guid"), XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, mapOf(
                    XBT_MANIFEST.PREV_XBT_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, XBT_MANIFEST.RELEASE_ID, XBT_MANIFEST.XBT_ID,
                            XBT, XBT.XBT_ID, XBT.GUID,
                            string(row, "prev_xbt_release_guid"), string(row, "prev_xbt_guid"))),
                    XBT_MANIFEST.NEXT_XBT_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, XBT_MANIFEST.RELEASE_ID, XBT_MANIFEST.XBT_ID,
                            XBT, XBT.XBT_ID, XBT.GUID,
                            string(row, "next_xbt_release_guid"), string(row, "next_xbt_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateDtManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("dt_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "dt_manifest", string(row, "dt_guid"), DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, mapOf(
                    DT_MANIFEST.BASED_DT_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.RELEASE_ID, DT_MANIFEST.DT_ID,
                            DT, DT.DT_ID, DT.GUID,
                            string(row, "based_dt_release_guid"), string(row, "based_dt_guid"))),
                    DT_MANIFEST.REPLACEMENT_DT_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.RELEASE_ID, DT_MANIFEST.DT_ID,
                            DT, DT.DT_ID, DT.GUID,
                            string(row, "replacement_dt_release_guid"), string(row, "replacement_dt_guid"))),
                    DT_MANIFEST.PREV_DT_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.RELEASE_ID, DT_MANIFEST.DT_ID,
                            DT, DT.DT_ID, DT.GUID,
                            string(row, "prev_dt_release_guid"), string(row, "prev_dt_guid"))),
                    DT_MANIFEST.NEXT_DT_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.RELEASE_ID, DT_MANIFEST.DT_ID,
                            DT, DT.DT_ID, DT.GUID,
                            string(row, "next_dt_release_guid"), string(row, "next_dt_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAccManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("acc_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "acc_manifest", string(row, "acc_guid"), ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, mapOf(
                    ACC_MANIFEST.BASED_ACC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.RELEASE_ID, ACC_MANIFEST.ACC_ID,
                            ACC, ACC.ACC_ID, ACC.GUID,
                            string(row, "based_acc_release_guid"), string(row, "based_acc_guid"))),
                    ACC_MANIFEST.REPLACEMENT_ACC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.RELEASE_ID, ACC_MANIFEST.ACC_ID,
                            ACC, ACC.ACC_ID, ACC.GUID,
                            string(row, "replacement_acc_release_guid"), string(row, "replacement_acc_guid"))),
                    ACC_MANIFEST.PREV_ACC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.RELEASE_ID, ACC_MANIFEST.ACC_ID,
                            ACC, ACC.ACC_ID, ACC.GUID,
                            string(row, "prev_acc_release_guid"), string(row, "prev_acc_guid"))),
                    ACC_MANIFEST.NEXT_ACC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.RELEASE_ID, ACC_MANIFEST.ACC_ID,
                            ACC, ACC.ACC_ID, ACC.GUID,
                            string(row, "next_acc_release_guid"), string(row, "next_acc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAsccpManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("asccp_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "asccp_manifest", string(row, "asccp_guid"), ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, mapOf(
                    ASCCP_MANIFEST.REPLACEMENT_ASCCP_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, ASCCP_MANIFEST.RELEASE_ID, ASCCP_MANIFEST.ASCCP_ID,
                            ASCCP, ASCCP.ASCCP_ID, ASCCP.GUID,
                            string(row, "replacement_asccp_release_guid"), string(row, "replacement_asccp_guid"))),
                    ASCCP_MANIFEST.PREV_ASCCP_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, ASCCP_MANIFEST.RELEASE_ID, ASCCP_MANIFEST.ASCCP_ID,
                            ASCCP, ASCCP.ASCCP_ID, ASCCP.GUID,
                            string(row, "prev_asccp_release_guid"), string(row, "prev_asccp_guid"))),
                    ASCCP_MANIFEST.NEXT_ASCCP_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, ASCCP_MANIFEST.RELEASE_ID, ASCCP_MANIFEST.ASCCP_ID,
                            ASCCP, ASCCP.ASCCP_ID, ASCCP.GUID,
                            string(row, "next_asccp_release_guid"), string(row, "next_asccp_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateBccpManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("bccp_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "bccp_manifest", string(row, "bccp_guid"), BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, mapOf(
                    BCCP_MANIFEST.REPLACEMENT_BCCP_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, BCCP_MANIFEST.RELEASE_ID, BCCP_MANIFEST.BCCP_ID,
                            BCCP, BCCP.BCCP_ID, BCCP.GUID,
                            string(row, "replacement_bccp_release_guid"), string(row, "replacement_bccp_guid"))),
                    BCCP_MANIFEST.PREV_BCCP_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, BCCP_MANIFEST.RELEASE_ID, BCCP_MANIFEST.BCCP_ID,
                            BCCP, BCCP.BCCP_ID, BCCP.GUID,
                            string(row, "prev_bccp_release_guid"), string(row, "prev_bccp_guid"))),
                    BCCP_MANIFEST.NEXT_BCCP_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, BCCP_MANIFEST.RELEASE_ID, BCCP_MANIFEST.BCCP_ID,
                            BCCP, BCCP.BCCP_ID, BCCP.GUID,
                            string(row, "next_bccp_release_guid"), string(row, "next_bccp_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateDtScManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("dt_sc_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "dt_sc_manifest", string(row, "dt_sc_guid"), DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, mapOf(
                    DT_SC_MANIFEST.BASED_DT_SC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, DT_SC_MANIFEST.RELEASE_ID, DT_SC_MANIFEST.DT_SC_ID,
                            DT_SC, DT_SC.DT_SC_ID, DT_SC.GUID,
                            string(row, "based_dt_sc_release_guid"), string(row, "based_dt_sc_guid"))),
                    DT_SC_MANIFEST.REPLACEMENT_DT_SC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, DT_SC_MANIFEST.RELEASE_ID, DT_SC_MANIFEST.DT_SC_ID,
                            DT_SC, DT_SC.DT_SC_ID, DT_SC.GUID,
                            string(row, "replacement_dt_sc_release_guid"), string(row, "replacement_dt_sc_guid"))),
                    DT_SC_MANIFEST.PREV_DT_SC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, DT_SC_MANIFEST.RELEASE_ID, DT_SC_MANIFEST.DT_SC_ID,
                            DT_SC, DT_SC.DT_SC_ID, DT_SC.GUID,
                            string(row, "prev_dt_sc_release_guid"), string(row, "prev_dt_sc_guid"))),
                    DT_SC_MANIFEST.NEXT_DT_SC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            DT_SC_MANIFEST, DT_SC_MANIFEST.DT_SC_MANIFEST_ID, DT_SC_MANIFEST.RELEASE_ID, DT_SC_MANIFEST.DT_SC_ID,
                            DT_SC, DT_SC.DT_SC_ID, DT_SC.GUID,
                            string(row, "next_dt_sc_release_guid"), string(row, "next_dt_sc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAsccManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("ascc_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "ascc_manifest", string(row, "ascc_guid"), ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, mapOf(
                    ASCC_MANIFEST.REPLACEMENT_ASCC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, ASCC_MANIFEST.RELEASE_ID, ASCC_MANIFEST.ASCC_ID,
                            ASCC, ASCC.ASCC_ID, ASCC.GUID,
                            string(row, "replacement_ascc_release_guid"), string(row, "replacement_ascc_guid"))),
                    ASCC_MANIFEST.PREV_ASCC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, ASCC_MANIFEST.RELEASE_ID, ASCC_MANIFEST.ASCC_ID,
                            ASCC, ASCC.ASCC_ID, ASCC.GUID,
                            string(row, "prev_ascc_release_guid"), string(row, "prev_ascc_guid"))),
                    ASCC_MANIFEST.NEXT_ASCC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            ASCC_MANIFEST, ASCC_MANIFEST.ASCC_MANIFEST_ID, ASCC_MANIFEST.RELEASE_ID, ASCC_MANIFEST.ASCC_ID,
                            ASCC, ASCC.ASCC_ID, ASCC.GUID,
                            string(row, "next_ascc_release_guid"), string(row, "next_ascc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateBccManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("bcc_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "bcc_manifest", string(row, "bcc_guid"), BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, mapOf(
                    BCC_MANIFEST.REPLACEMENT_BCC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, BCC_MANIFEST.RELEASE_ID, BCC_MANIFEST.BCC_ID,
                            BCC, BCC.BCC_ID, BCC.GUID,
                            string(row, "replacement_bcc_release_guid"), string(row, "replacement_bcc_guid"))),
                    BCC_MANIFEST.PREV_BCC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, BCC_MANIFEST.RELEASE_ID, BCC_MANIFEST.BCC_ID,
                            BCC, BCC.BCC_ID, BCC.GUID,
                            string(row, "prev_bcc_release_guid"), string(row, "prev_bcc_guid"))),
                    BCC_MANIFEST.NEXT_BCC_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            BCC_MANIFEST, BCC_MANIFEST.BCC_MANIFEST_ID, BCC_MANIFEST.RELEASE_ID, BCC_MANIFEST.BCC_ID,
                            BCC, BCC.BCC_ID, BCC.GUID,
                            string(row, "next_bcc_release_guid"), string(row, "next_bcc_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateCodeListManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("code_list_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "code_list_manifest", string(row, "code_list_guid"), CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, mapOf(
                    CODE_LIST_MANIFEST.BASED_CODE_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.RELEASE_ID, CODE_LIST_MANIFEST.CODE_LIST_ID,
                            CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID,
                            string(row, "based_code_list_release_guid"), string(row, "based_code_list_guid"))),
                    CODE_LIST_MANIFEST.REPLACEMENT_CODE_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.RELEASE_ID, CODE_LIST_MANIFEST.CODE_LIST_ID,
                            CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID,
                            string(row, "replacement_code_list_release_guid"), string(row, "replacement_code_list_guid"))),
                    CODE_LIST_MANIFEST.PREV_CODE_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.RELEASE_ID, CODE_LIST_MANIFEST.CODE_LIST_ID,
                            CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID,
                            string(row, "prev_code_list_release_guid"), string(row, "prev_code_list_guid"))),
                    CODE_LIST_MANIFEST.NEXT_CODE_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.RELEASE_ID, CODE_LIST_MANIFEST.CODE_LIST_ID,
                            CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID,
                            string(row, "next_code_list_release_guid"), string(row, "next_code_list_guid"))),
                    CODE_LIST_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, nullableULong(resolveGuidId(context, "agency_id_list_value_manifest", string(row, "agency_id_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateCodeListValueManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("code_list_value_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "code_list_value_manifest", string(row, "code_list_value_guid"), CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, mapOf(
                    CODE_LIST_VALUE_MANIFEST.BASED_CODE_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, CODE_LIST_VALUE_MANIFEST.RELEASE_ID, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID,
                            CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, CODE_LIST_VALUE.GUID,
                            string(row, "based_code_list_value_release_guid"), string(row, "based_code_list_value_guid"))),
                    CODE_LIST_VALUE_MANIFEST.REPLACEMENT_CODE_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, CODE_LIST_VALUE_MANIFEST.RELEASE_ID, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID,
                            CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, CODE_LIST_VALUE.GUID,
                            string(row, "replacement_code_list_value_release_guid"), string(row, "replacement_code_list_value_guid"))),
                    CODE_LIST_VALUE_MANIFEST.PREV_CODE_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, CODE_LIST_VALUE_MANIFEST.RELEASE_ID, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID,
                            CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, CODE_LIST_VALUE.GUID,
                            string(row, "prev_code_list_value_release_guid"), string(row, "prev_code_list_value_guid"))),
                    CODE_LIST_VALUE_MANIFEST.NEXT_CODE_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            CODE_LIST_VALUE_MANIFEST, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID, CODE_LIST_VALUE_MANIFEST.RELEASE_ID, CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID,
                            CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, CODE_LIST_VALUE.GUID,
                            string(row, "next_code_list_value_release_guid"), string(row, "next_code_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAgencyIdListManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("agency_id_list_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "agency_id_list_manifest", string(row, "agency_id_list_guid"), AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, mapOf(
                    AGENCY_ID_LIST_MANIFEST.BASED_AGENCY_ID_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID,
                            AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID,
                            string(row, "based_agency_id_list_release_guid"), string(row, "based_agency_id_list_guid"))),
                    AGENCY_ID_LIST_MANIFEST.REPLACEMENT_AGENCY_ID_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID,
                            AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID,
                            string(row, "replacement_agency_id_list_release_guid"), string(row, "replacement_agency_id_list_guid"))),
                    AGENCY_ID_LIST_MANIFEST.PREV_AGENCY_ID_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID,
                            AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID,
                            string(row, "prev_agency_id_list_release_guid"), string(row, "prev_agency_id_list_guid"))),
                    AGENCY_ID_LIST_MANIFEST.NEXT_AGENCY_ID_LIST_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID,
                            AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID,
                            string(row, "next_agency_id_list_release_guid"), string(row, "next_agency_id_list_guid"))),
                    AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, nullableULong(resolveGuidId(context, "agency_id_list_value_manifest", string(row, "agency_id_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateAgencyIdListValueManifestLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("agency_id_list_value_manifest")) {
            addGuidMappedSelfLinkUpdate(queries, context, "agency_id_list_value_manifest", string(row, "agency_id_list_value_guid"), AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, mapOf(
                    AGENCY_ID_LIST_VALUE_MANIFEST.BASED_AGENCY_ID_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID,
                            AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, AGENCY_ID_LIST_VALUE.GUID,
                            string(row, "based_agency_id_list_value_release_guid"), string(row, "based_agency_id_list_value_guid"))),
                    AGENCY_ID_LIST_VALUE_MANIFEST.REPLACEMENT_AGENCY_ID_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID,
                            AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, AGENCY_ID_LIST_VALUE.GUID,
                            string(row, "replacement_agency_id_list_value_release_guid"), string(row, "replacement_agency_id_list_value_guid"))),
                    AGENCY_ID_LIST_VALUE_MANIFEST.PREV_AGENCY_ID_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID,
                            AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, AGENCY_ID_LIST_VALUE.GUID,
                            string(row, "prev_agency_id_list_value_release_guid"), string(row, "prev_agency_id_list_value_guid"))),
                    AGENCY_ID_LIST_VALUE_MANIFEST.NEXT_AGENCY_ID_LIST_VALUE_MANIFEST_ID, nullableULong(resolveManifestLinkId(
                            AGENCY_ID_LIST_VALUE_MANIFEST, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID, AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID, AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID,
                            AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, AGENCY_ID_LIST_VALUE.GUID,
                            string(row, "next_agency_id_list_value_release_guid"), string(row, "next_agency_id_list_value_guid")))
            ));
        }
        executeBatch(queries);
    }

    private void updateBlobContentManifestLinks(ImportContext context) {
        for (Map<String, Object> row : context.rows("blob_content_manifest")) {
            BigInteger id = context.idByInternal("blob_content_manifest").get(internalId(row));
            if (id == null) {
                continue;
            }
            updateRowById(BLOB_CONTENT_MANIFEST, BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID, id, mapOf(
                    BLOB_CONTENT_MANIFEST.PREV_BLOB_CONTENT_MANIFEST_ID, nullableULong(resolveInternalId(context, "blob_content_manifest", number(row, "prev_blob_content_manifest_internal_id"))),
                    BLOB_CONTENT_MANIFEST.NEXT_BLOB_CONTENT_MANIFEST_ID, nullableULong(resolveInternalId(context, "blob_content_manifest", number(row, "next_blob_content_manifest_internal_id")))
            ));
        }
    }

    private void importSeqKeys(ImportContext context) {
        for (Map<String, Object> row : context.rows("seq_key")) {
            BigInteger fromAccManifestId = resolveGuidId(context, "acc_manifest", string(row, "from_acc_guid"));
            BigInteger asccManifestId = resolveGuidId(context, "ascc_manifest", string(row, "ascc_guid"));
            BigInteger bccManifestId = resolveGuidId(context, "bcc_manifest", string(row, "bcc_guid"));

            Condition condition = SEQ_KEY.FROM_ACC_MANIFEST_ID.eq(toULong(fromAccManifestId));
            if (asccManifestId != null) {
                condition = condition.and(SEQ_KEY.ASCC_MANIFEST_ID.eq(toULong(asccManifestId)));
            } else {
                condition = condition.and(SEQ_KEY.ASCC_MANIFEST_ID.isNull());
            }
            if (bccManifestId != null) {
                condition = condition.and(SEQ_KEY.BCC_MANIFEST_ID.eq(toULong(bccManifestId)));
            } else {
                condition = condition.and(SEQ_KEY.BCC_MANIFEST_ID.isNull());
            }

            BigInteger id = findId(SEQ_KEY, SEQ_KEY.SEQ_KEY_ID, condition);
            if (id == null) {
                id = insertRow(SEQ_KEY, SEQ_KEY.SEQ_KEY_ID, internalId(row), mapOf(
                        SEQ_KEY.FROM_ACC_MANIFEST_ID, toULong(fromAccManifestId),
                        SEQ_KEY.ASCC_MANIFEST_ID, nullableULong(asccManifestId),
                        SEQ_KEY.BCC_MANIFEST_ID, nullableULong(bccManifestId)
                ));
            }
            context.idByInternal("seq_key").put(internalId(row), id);
        }
    }

    private void updateSeqKeyLinks(ImportContext context) {
        List<Query> queries = new ArrayList<>();
        for (Map<String, Object> row : context.rows("seq_key")) {
            BigInteger id = resolveInternalId(context, "seq_key", internalId(row));
            if (id == null) {
                continue;
            }
            ULong prevSeqKeyId = nullableULong(resolveInternalId(context, "seq_key", number(row, "prev_seq_key_internal_id")));
            ULong nextSeqKeyId = nullableULong(resolveInternalId(context, "seq_key", number(row, "next_seq_key_internal_id")));
            queries.add(dslContext().update(SEQ_KEY)
                    .set(SEQ_KEY.PREV_SEQ_KEY_ID, prevSeqKeyId)
                    .set(SEQ_KEY.NEXT_SEQ_KEY_ID, nextSeqKeyId)
                    .where(SEQ_KEY.SEQ_KEY_ID.eq(toULong(id))));
        }
        if (!queries.isEmpty()) {
            dslContext().batch(queries).execute();
        }
    }

    private void importDtAwdPris(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt_awd_pri")) {
            BigInteger dtId = resolveGuidId(context, "dt", string(row, "dt_guid"));
            BigInteger xbtManifestId = resolveGuidId(context, "xbt_manifest", string(row, "xbt_guid"));
            BigInteger codeListManifestId = resolveGuidId(context, "code_list_manifest", string(row, "code_list_guid"));
            BigInteger agencyIdListManifestId = resolveGuidId(context, "agency_id_list_manifest", string(row, "agency_id_list_guid"));
            String cdtPriName = string(row, "cdt_pri_name");
            String xbtManifestCdtPriName = string(findRow(context.rows("xbt_manifest"), "xbt_guid", string(row, "xbt_guid")), "cdt_pri_name");
            BigInteger cdtPriId = StringUtils.hasText(cdtPriName) ?
                    resolveCdtPriId(cdtPriName) :
                    (StringUtils.hasText(xbtManifestCdtPriName) ?
                            resolveCdtPriId(xbtManifestCdtPriName) :
                            resolveAllowedPrimitiveCdtPriId(context, xbtManifestId, codeListManifestId, agencyIdListManifestId));
            if (cdtPriId == null) {
                throw new IllegalArgumentException("Unable to resolve CDT primitive for dt_awd_pri row. dt_guid="
                        + string(row, "dt_guid") + ", xbt_guid=" + string(row, "xbt_guid"));
            }

            Condition condition = DT_AWD_PRI.RELEASE_ID.eq(toULong(context.releaseId))
                    .and(DT_AWD_PRI.DT_ID.eq(toULong(dtId)))
                    .and(nullableCondition(DT_AWD_PRI.XBT_MANIFEST_ID, xbtManifestId))
                    .and(nullableCondition(DT_AWD_PRI.CODE_LIST_MANIFEST_ID, codeListManifestId))
                    .and(nullableCondition(DT_AWD_PRI.AGENCY_ID_LIST_MANIFEST_ID, agencyIdListManifestId));
            BigInteger id = findId(DT_AWD_PRI, DT_AWD_PRI.DT_AWD_PRI_ID, condition);
            if (id == null) {
                insertRow(DT_AWD_PRI, DT_AWD_PRI.DT_AWD_PRI_ID, internalId(row), mapOf(
                        DT_AWD_PRI.RELEASE_ID, toULong(context.releaseId),
                        DT_AWD_PRI.DT_ID, toULong(dtId),
                        DT_AWD_PRI.CDT_PRI_ID, toULong(cdtPriId),
                        DT_AWD_PRI.XBT_MANIFEST_ID, nullableULong(xbtManifestId),
                        DT_AWD_PRI.CODE_LIST_MANIFEST_ID, nullableULong(codeListManifestId),
                        DT_AWD_PRI.AGENCY_ID_LIST_MANIFEST_ID, nullableULong(agencyIdListManifestId),
                        DT_AWD_PRI.IS_DEFAULT, bit(row, "is_default")
                ));
            }
        }
    }

    private void importDtScAwdPris(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt_sc_awd_pri")) {
            BigInteger dtScId = resolveGuidId(context, "dt_sc", string(row, "dt_sc_guid"));
            BigInteger xbtManifestId = resolveGuidId(context, "xbt_manifest", string(row, "xbt_guid"));
            BigInteger codeListManifestId = resolveGuidId(context, "code_list_manifest", string(row, "code_list_guid"));
            BigInteger agencyIdListManifestId = resolveGuidId(context, "agency_id_list_manifest", string(row, "agency_id_list_guid"));
            String cdtPriName = string(row, "cdt_pri_name");
            String xbtManifestCdtPriName = string(findRow(context.rows("xbt_manifest"), "xbt_guid", string(row, "xbt_guid")), "cdt_pri_name");
            BigInteger cdtPriId = StringUtils.hasText(cdtPriName) ?
                    resolveCdtPriId(cdtPriName) :
                    (StringUtils.hasText(xbtManifestCdtPriName) ?
                            resolveCdtPriId(xbtManifestCdtPriName) :
                            resolveAllowedPrimitiveCdtPriId(context, xbtManifestId, codeListManifestId, agencyIdListManifestId));
            if (cdtPriId == null) {
                throw new IllegalArgumentException("Unable to resolve CDT primitive for dt_sc_awd_pri row. dt_sc_guid="
                        + string(row, "dt_sc_guid") + ", xbt_guid=" + string(row, "xbt_guid"));
            }

            Condition condition = DT_SC_AWD_PRI.RELEASE_ID.eq(toULong(context.releaseId))
                    .and(DT_SC_AWD_PRI.DT_SC_ID.eq(toULong(dtScId)))
                    .and(nullableCondition(DT_SC_AWD_PRI.XBT_MANIFEST_ID, xbtManifestId))
                    .and(nullableCondition(DT_SC_AWD_PRI.CODE_LIST_MANIFEST_ID, codeListManifestId))
                    .and(nullableCondition(DT_SC_AWD_PRI.AGENCY_ID_LIST_MANIFEST_ID, agencyIdListManifestId));
            BigInteger id = findId(DT_SC_AWD_PRI, DT_SC_AWD_PRI.DT_SC_AWD_PRI_ID, condition);
            if (id == null) {
                insertRow(DT_SC_AWD_PRI, DT_SC_AWD_PRI.DT_SC_AWD_PRI_ID, internalId(row), mapOf(
                        DT_SC_AWD_PRI.RELEASE_ID, toULong(context.releaseId),
                        DT_SC_AWD_PRI.DT_SC_ID, toULong(dtScId),
                        DT_SC_AWD_PRI.CDT_PRI_ID, toULong(cdtPriId),
                        DT_SC_AWD_PRI.XBT_MANIFEST_ID, nullableULong(xbtManifestId),
                        DT_SC_AWD_PRI.CODE_LIST_MANIFEST_ID, nullableULong(codeListManifestId),
                        DT_SC_AWD_PRI.AGENCY_ID_LIST_MANIFEST_ID, nullableULong(agencyIdListManifestId),
                        DT_SC_AWD_PRI.IS_DEFAULT, bit(row, "is_default")
                ));
            }
        }
    }

    private void importReleaseDeps(ImportContext context) {
        for (Map<String, Object> row : context.rows("release_dep")) {
            BigInteger dependOnReleaseId = findId(RELEASE, RELEASE.RELEASE_ID, RELEASE.GUID.eq(string(row, "depend_on_release_guid")));
            if (dependOnReleaseId == null) {
                throw new IllegalArgumentException("Dependent release '" + string(row, "depend_on_release_num") + "' does not exist.");
            }
            Condition condition = RELEASE_DEP.RELEASE_ID.eq(toULong(context.releaseId))
                    .and(RELEASE_DEP.DEPEND_ON_RELEASE_ID.eq(toULong(dependOnReleaseId)));
            if (findId(RELEASE_DEP, RELEASE_DEP.RELEASE_DEP_ID, condition) == null) {
                insertRow(RELEASE_DEP, RELEASE_DEP.RELEASE_DEP_ID, internalId(row), mapOf(
                        RELEASE_DEP.RELEASE_ID, toULong(context.releaseId),
                        RELEASE_DEP.DEPEND_ON_RELEASE_ID, toULong(dependOnReleaseId)
                ));
            }
        }
    }

    private void importAccManifestTags(ImportContext context) {
        for (Map<String, Object> row : context.rows("acc_manifest_tag")) {
            BigInteger accManifestId = resolveGuidId(context, "acc_manifest", string(row, "acc_guid"));
            BigInteger tagId = context.idByName("tag").get(string(row, "tag_name"));
            insertManifestTagIfAbsent(ACC_MANIFEST_TAG,
                    ACC_MANIFEST_TAG.ACC_MANIFEST_ID, accManifestId,
                    ACC_MANIFEST_TAG.TAG_ID, tagId,
                    ACC_MANIFEST_TAG.CREATED_BY, resolveUserId(context, string(row, "created_by_login_id")),
                    ACC_MANIFEST_TAG.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", LocalDateTime.now()),
                    internalId(row));
        }
    }

    private void importAsccpManifestTags(ImportContext context) {
        for (Map<String, Object> row : context.rows("asccp_manifest_tag")) {
            BigInteger manifestId = resolveGuidId(context, "asccp_manifest", string(row, "asccp_guid"));
            BigInteger tagId = context.idByName("tag").get(string(row, "tag_name"));
            insertManifestTagIfAbsent(ASCCP_MANIFEST_TAG,
                    ASCCP_MANIFEST_TAG.ASCCP_MANIFEST_ID, manifestId,
                    ASCCP_MANIFEST_TAG.TAG_ID, tagId,
                    ASCCP_MANIFEST_TAG.CREATED_BY, resolveUserId(context, string(row, "created_by_login_id")),
                    ASCCP_MANIFEST_TAG.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", LocalDateTime.now()),
                    internalId(row));
        }
    }

    private void importBccpManifestTags(ImportContext context) {
        for (Map<String, Object> row : context.rows("bccp_manifest_tag")) {
            BigInteger manifestId = resolveGuidId(context, "bccp_manifest", string(row, "bccp_guid"));
            BigInteger tagId = context.idByName("tag").get(string(row, "tag_name"));
            insertManifestTagIfAbsent(BCCP_MANIFEST_TAG,
                    BCCP_MANIFEST_TAG.BCCP_MANIFEST_ID, manifestId,
                    BCCP_MANIFEST_TAG.TAG_ID, tagId,
                    BCCP_MANIFEST_TAG.CREATED_BY, resolveUserId(context, string(row, "created_by_login_id")),
                    BCCP_MANIFEST_TAG.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", LocalDateTime.now()),
                    internalId(row));
        }
    }

    private void importDtManifestTags(ImportContext context) {
        for (Map<String, Object> row : context.rows("dt_manifest_tag")) {
            BigInteger manifestId = resolveGuidId(context, "dt_manifest", string(row, "dt_guid"));
            BigInteger tagId = context.idByName("tag").get(string(row, "tag_name"));
            insertManifestTagIfAbsent(DT_MANIFEST_TAG,
                    DT_MANIFEST_TAG.DT_MANIFEST_ID, manifestId,
                    DT_MANIFEST_TAG.TAG_ID, tagId,
                    DT_MANIFEST_TAG.CREATED_BY, resolveUserId(context, string(row, "created_by_login_id")),
                    DT_MANIFEST_TAG.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", LocalDateTime.now()),
                    internalId(row));
        }
    }

    private void importModuleAccManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_acc_manifest", "acc_manifest", "acc_guid",
                MODULE_ACC_MANIFEST, MODULE_ACC_MANIFEST.MODULE_ACC_MANIFEST_ID, MODULE_ACC_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_ACC_MANIFEST.MODULE_ID, MODULE_ACC_MANIFEST.ACC_MANIFEST_ID,
                MODULE_ACC_MANIFEST.CREATED_BY, MODULE_ACC_MANIFEST.LAST_UPDATED_BY,
                MODULE_ACC_MANIFEST.CREATION_TIMESTAMP, MODULE_ACC_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleAgencyIdListManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_agency_id_list_manifest", "agency_id_list_manifest", "agency_id_list_guid",
                MODULE_AGENCY_ID_LIST_MANIFEST, MODULE_AGENCY_ID_LIST_MANIFEST.MODULE_AGENCY_ID_LIST_MANIFEST_ID, MODULE_AGENCY_ID_LIST_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_AGENCY_ID_LIST_MANIFEST.MODULE_ID, MODULE_AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID,
                MODULE_AGENCY_ID_LIST_MANIFEST.CREATED_BY, MODULE_AGENCY_ID_LIST_MANIFEST.LAST_UPDATED_BY,
                MODULE_AGENCY_ID_LIST_MANIFEST.CREATION_TIMESTAMP, MODULE_AGENCY_ID_LIST_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleAsccpManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_asccp_manifest", "asccp_manifest", "asccp_guid",
                MODULE_ASCCP_MANIFEST, MODULE_ASCCP_MANIFEST.MODULE_ASCCP_MANIFEST_ID, MODULE_ASCCP_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_ASCCP_MANIFEST.MODULE_ID, MODULE_ASCCP_MANIFEST.ASCCP_MANIFEST_ID,
                MODULE_ASCCP_MANIFEST.CREATED_BY, MODULE_ASCCP_MANIFEST.LAST_UPDATED_BY,
                MODULE_ASCCP_MANIFEST.CREATION_TIMESTAMP, MODULE_ASCCP_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleBccpManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_bccp_manifest", "bccp_manifest", "bccp_guid",
                MODULE_BCCP_MANIFEST, MODULE_BCCP_MANIFEST.MODULE_BCCP_MANIFEST_ID, MODULE_BCCP_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_BCCP_MANIFEST.MODULE_ID, MODULE_BCCP_MANIFEST.BCCP_MANIFEST_ID,
                MODULE_BCCP_MANIFEST.CREATED_BY, MODULE_BCCP_MANIFEST.LAST_UPDATED_BY,
                MODULE_BCCP_MANIFEST.CREATION_TIMESTAMP, MODULE_BCCP_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleCodeListManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_code_list_manifest", "code_list_manifest", "code_list_guid",
                MODULE_CODE_LIST_MANIFEST, MODULE_CODE_LIST_MANIFEST.MODULE_CODE_LIST_MANIFEST_ID, MODULE_CODE_LIST_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_CODE_LIST_MANIFEST.MODULE_ID, MODULE_CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID,
                MODULE_CODE_LIST_MANIFEST.CREATED_BY, MODULE_CODE_LIST_MANIFEST.LAST_UPDATED_BY,
                MODULE_CODE_LIST_MANIFEST.CREATION_TIMESTAMP, MODULE_CODE_LIST_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleDtManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_dt_manifest", "dt_manifest", "dt_guid",
                MODULE_DT_MANIFEST, MODULE_DT_MANIFEST.MODULE_DT_MANIFEST_ID, MODULE_DT_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_DT_MANIFEST.MODULE_ID, MODULE_DT_MANIFEST.DT_MANIFEST_ID,
                MODULE_DT_MANIFEST.CREATED_BY, MODULE_DT_MANIFEST.LAST_UPDATED_BY,
                MODULE_DT_MANIFEST.CREATION_TIMESTAMP, MODULE_DT_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleXbtManifests(ImportContext context) {
        importModuleManifestAssignments(context, "module_xbt_manifest", "xbt_manifest", "xbt_guid",
                MODULE_XBT_MANIFEST, MODULE_XBT_MANIFEST.MODULE_XBT_MANIFEST_ID, MODULE_XBT_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_XBT_MANIFEST.MODULE_ID, MODULE_XBT_MANIFEST.XBT_MANIFEST_ID,
                MODULE_XBT_MANIFEST.CREATED_BY, MODULE_XBT_MANIFEST.LAST_UPDATED_BY,
                MODULE_XBT_MANIFEST.CREATION_TIMESTAMP, MODULE_XBT_MANIFEST.LAST_UPDATE_TIMESTAMP);
    }

    private void importModuleBlobContentManifests(ImportContext context) {
        for (Map<String, Object> row : context.rows("module_blob_content_manifest")) {
            BigInteger moduleSetReleaseId = resolveModuleSetReleaseId(context, string(row, "module_set_guid"), string(row, "module_set_release_name"));
            BigInteger moduleId = resolveModuleId(context, string(row, "module_set_guid"), string(row, "module_path"));
            BigInteger blobContentManifestId = resolveInternalId(context, "blob_content_manifest", number(row, "blob_content_manifest_internal_id"));
            Condition condition = MODULE_BLOB_CONTENT_MANIFEST.MODULE_SET_RELEASE_ID.eq(toULong(moduleSetReleaseId))
                    .and(MODULE_BLOB_CONTENT_MANIFEST.MODULE_ID.eq(toULong(moduleId)))
                    .and(MODULE_BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID.eq(toULong(blobContentManifestId)));
            if (findId(MODULE_BLOB_CONTENT_MANIFEST, MODULE_BLOB_CONTENT_MANIFEST.MODULE_BLOB_CONTENT_MANIFEST_ID, condition) == null) {
                LocalDateTime now = LocalDateTime.now();
                insertRow(MODULE_BLOB_CONTENT_MANIFEST, MODULE_BLOB_CONTENT_MANIFEST.MODULE_BLOB_CONTENT_MANIFEST_ID, internalId(row), mapOf(
                        MODULE_BLOB_CONTENT_MANIFEST.MODULE_SET_RELEASE_ID, toULong(moduleSetReleaseId),
                        MODULE_BLOB_CONTENT_MANIFEST.MODULE_ID, toULong(moduleId),
                        MODULE_BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID, toULong(blobContentManifestId),
                        MODULE_BLOB_CONTENT_MANIFEST.CREATED_BY, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        MODULE_BLOB_CONTENT_MANIFEST.LAST_UPDATED_BY, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        MODULE_BLOB_CONTENT_MANIFEST.CREATION_TIMESTAMP, timestamp(row, "creation_timestamp", now),
                        MODULE_BLOB_CONTENT_MANIFEST.LAST_UPDATE_TIMESTAMP, timestamp(row, "last_update_timestamp", now)
                ));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void importModuleManifestAssignments(
            ImportContext context,
            String rowTable,
            String manifestTableKey,
            String guidFieldName,
            Table assignmentTable,
            TableField assignmentIdField,
            TableField moduleSetReleaseIdField,
            TableField moduleIdField,
            TableField manifestIdField,
            TableField createdByField,
            TableField lastUpdatedByField,
            TableField creationTimestampField,
            TableField lastUpdateTimestampField) {

        for (Map<String, Object> row : context.rows(rowTable)) {
            BigInteger moduleSetReleaseId = resolveModuleSetReleaseId(context, string(row, "module_set_guid"), string(row, "module_set_release_name"));
            BigInteger moduleId = resolveModuleId(context, string(row, "module_set_guid"), string(row, "module_path"));
            BigInteger manifestId = resolveGuidId(context, manifestTableKey, string(row, guidFieldName));
            Condition condition = ((Field<ULong>) moduleSetReleaseIdField).eq(toULong(moduleSetReleaseId))
                    .and(((Field<ULong>) moduleIdField).eq(toULong(moduleId)))
                    .and(((Field<ULong>) manifestIdField).eq(toULong(manifestId)));
            if (findId(assignmentTable, assignmentIdField, condition) == null) {
                LocalDateTime now = LocalDateTime.now();
                insertRow(assignmentTable, assignmentIdField, internalId(row), mapOf(
                        moduleSetReleaseIdField, toULong(moduleSetReleaseId),
                        moduleIdField, toULong(moduleId),
                        manifestIdField, toULong(manifestId),
                        createdByField, toULong(resolveUserId(context, string(row, "created_by_login_id"))),
                        lastUpdatedByField, toULong(resolveUserId(context, string(row, "last_updated_by_login_id"))),
                        creationTimestampField, timestamp(row, "creation_timestamp", now),
                        lastUpdateTimestampField, timestamp(row, "last_update_timestamp", now)
                ));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void insertManifestTagIfAbsent(
            Table table,
            TableField manifestField,
            BigInteger manifestId,
            TableField tagField,
            BigInteger tagId,
            TableField createdByField,
            BigInteger createdById,
            TableField creationTimestampField,
            LocalDateTime creationTimestamp,
            BigInteger hintId) {
        Condition condition = ((Field<ULong>) manifestField).eq(toULong(manifestId))
                .and(((Field<ULong>) tagField).eq(toULong(tagId)));
        if (findAny(table, condition) == null) {
            insertCompositeRow(table, mapOf(
                    manifestField, toULong(manifestId),
                    tagField, toULong(tagId),
                    createdByField, toULong(createdById),
                    creationTimestampField, creationTimestamp
            ));
        }
    }

    private BigInteger resolveAllowedPrimitiveCdtPriId(ImportContext context,
                                                       BigInteger xbtManifestId,
                                                       BigInteger codeListManifestId,
                                                       BigInteger agencyIdListManifestId) {
        if (xbtManifestId != null) {
            return findId(XBT_MANIFEST, XBT_MANIFEST.CDT_PRI_ID, XBT_MANIFEST.XBT_MANIFEST_ID.eq(toULong(xbtManifestId)));
        }
        if (codeListManifestId != null) {
            return resolveCdtPriId("Token");
        }
        if (agencyIdListManifestId != null) {
            return resolveCdtPriId("Token");
        }
        throw new IllegalArgumentException("Allowed primitive mapping is missing.");
    }

    private BigInteger resolveGuidId(ImportContext context, String table, String guid) {
        if (!StringUtils.hasText(guid)) {
            return null;
        }
        BigInteger id = context.idByGuid(table).get(guid);
        if (id != null) {
            return id;
        }

        id = findExistingGuidId(table, guid);
        if (id != null) {
            context.idByGuid(table).put(guid, id);
        }
        return id;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BigInteger findExistingGuidId(String table, String guid) {
        return switch (table) {
            case "xbt" -> findId(XBT, XBT.XBT_ID, XBT.GUID.eq(guid));
            case "dt" -> findId(DT, DT.DT_ID, DT.GUID.eq(guid));
            case "acc" -> findId(ACC, ACC.ACC_ID, ACC.GUID.eq(guid));
            case "asccp" -> findId(ASCCP, ASCCP.ASCCP_ID, ASCCP.GUID.eq(guid));
            case "bccp" -> findId(BCCP, BCCP.BCCP_ID, BCCP.GUID.eq(guid));
            case "ascc" -> findId(ASCC, ASCC.ASCC_ID, ASCC.GUID.eq(guid));
            case "bcc" -> findId(BCC, BCC.BCC_ID, BCC.GUID.eq(guid));
            case "dt_sc" -> findId(DT_SC, DT_SC.DT_SC_ID, DT_SC.GUID.eq(guid));
            case "code_list" -> findId(CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID.eq(guid));
            case "code_list_value" -> findId(CODE_LIST_VALUE, CODE_LIST_VALUE.CODE_LIST_VALUE_ID, CODE_LIST_VALUE.GUID.eq(guid));
            case "agency_id_list" -> findId(AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID.eq(guid));
            case "agency_id_list_value" -> findId(AGENCY_ID_LIST_VALUE, AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID, AGENCY_ID_LIST_VALUE.GUID.eq(guid));
            default -> null;
        };
    }

    private BigInteger resolveModuleSetReleaseId(ImportContext context, String moduleSetGuid, String name) {
        if (!StringUtils.hasText(moduleSetGuid) || !StringUtils.hasText(name)) {
            return null;
        }
        return context.idByKey("module_set_release").get(context.moduleSetReleaseKey(moduleSetGuid, name));
    }

    private BigInteger resolveModuleId(ImportContext context, String moduleSetGuid, String path) {
        if (!StringUtils.hasText(moduleSetGuid) || !StringUtils.hasText(path)) {
            return null;
        }
        return context.idByKey("module").get(context.moduleKey(moduleSetGuid, path));
    }

    private BigInteger resolveNamespaceId(ImportContext context, String namespaceUri) {
        if (!StringUtils.hasText(namespaceUri)) {
            return null;
        }
        if (context.namespaceId != null && namespaceUri.equals(string(context.firstRow("namespace"), "uri"))) {
            return context.namespaceId;
        }
        return findId(NAMESPACE, NAMESPACE.NAMESPACE_ID,
                NAMESPACE.LIBRARY_ID.eq(toULong(context.libraryId))
                        .and(NAMESPACE.URI.eq(namespaceUri)));
    }

    private BigInteger resolveUserId(ImportContext context, String loginId) {
        if (!StringUtils.hasText(loginId)) {
            return requester().userId().value();
        }
        return context.userIdByLoginId.computeIfAbsent(loginId, key -> {
            BigInteger userId = findId(APP_USER, APP_USER.APP_USER_ID, APP_USER.LOGIN_ID.eq(key));
            return (userId != null) ? userId : requester().userId().value();
        });
    }

    private BigInteger resolveInternalId(ImportContext context, String table, BigInteger oldInternalId) {
        if (oldInternalId == null) {
            return null;
        }
        return context.idByInternal(table).get(oldInternalId);
    }

    private BigInteger resolveCdtPriId(String cdtPriName) {
        if (!StringUtils.hasText(cdtPriName)) {
            return null;
        }
        return findId(CDT_PRI, CDT_PRI.CDT_PRI_ID, CDT_PRI.NAME.eq(cdtPriName));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BigInteger resolveManifestLinkId(Table manifestTable,
                                             TableField manifestPkField,
                                             TableField manifestReleaseField,
                                             TableField manifestRawField,
                                             Table rawTable,
                                             TableField rawPkField,
                                             TableField rawGuidField,
                                             String releaseGuid,
                                             String rawGuid) {
        if (!StringUtils.hasText(releaseGuid) || !StringUtils.hasText(rawGuid)) {
            return null;
        }
        BigInteger releaseId = findId(RELEASE, RELEASE.RELEASE_ID, RELEASE.GUID.eq(releaseGuid));
        if (releaseId == null) {
            return null;
        }
        BigInteger rawId = findId(rawTable, rawPkField, ((Field<String>) rawGuidField).eq(rawGuid));
        if (rawId == null) {
            return null;
        }
        return findId(manifestTable, manifestPkField,
                ((Field<ULong>) manifestReleaseField).eq(toULong(releaseId))
                        .and(((Field<ULong>) manifestRawField).eq(toULong(rawId))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BigInteger findManifestId(Table table, TableField pkField, TableField releaseField, TableField rawField,
                                      BigInteger releaseId, BigInteger rawId) {
        return findId(table, pkField,
                ((Field<ULong>) releaseField).eq(toULong(releaseId))
                        .and(((Field<ULong>) rawField).eq(toULong(rawId))));
    }

    private void addGuidMappedSelfLinkUpdate(List<Query> queries, ImportContext context, String guidTableKey, String guid,
                                             Table<?> table, TableField<?, ULong> pkField, LinkedHashMap<Field<?>, Object> values) {
        BigInteger id = resolveGuidId(context, guidTableKey, guid);
        if (id == null) {
            return;
        }
        Query query = buildUpdateByIdQuery(table, pkField, id, values);
        if (query != null) {
            queries.add(query);
        }
    }

    private void executeBatch(List<Query> queries) {
        if (queries != null && !queries.isEmpty()) {
            dslContext().batch(queries).execute();
        }
    }

    private Condition stringEquals(Field<String> field, String value) {
        return value == null ? field.isNull() : field.eq(value);
    }

    @SuppressWarnings("unchecked")
    private Condition nullableCondition(TableField<?, ULong> field, BigInteger value) {
        return (value == null) ? ((Field<ULong>) field).isNull() : ((Field<ULong>) field).eq(toULong(value));
    }

    private String value(Map<String, Object> row, String key, String fallback) {
        String value = string(row, key);
        return (value != null) ? value : fallback;
    }

    private Map<String, Object> findRow(List<Map<String, Object>> rows, String key, String value) {
        if (rows == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            if (value.equals(string(row, key))) {
                return row;
            }
        }
        return null;
    }

    private String string(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key) || row.get(key) == null) {
            return null;
        }
        return String.valueOf(row.get(key));
    }

    private Integer integer(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key) || row.get(key) == null) {
            return null;
        }
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            return Integer.parseInt(str);
        }
        return null;
    }

    private Boolean bool(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key) || row.get(key) == null) {
            return null;
        }
        Object value = row.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            return Boolean.parseBoolean(str) || "1".equals(str);
        }
        return null;
    }

    private Byte bit(Map<String, Object> row, String key) {
        Boolean bool = bool(row, key);
        return bool == null ? null : (byte) (bool ? 1 : 0);
    }

    private BigInteger number(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key) || row.get(key) == null) {
            return null;
        }
        return toBigInteger((Number) row.get(key));
    }

    private BigInteger internalId(Map<String, Object> row) {
        if (row == null || !row.containsKey("_internal_id") || row.get("_internal_id") == null) {
            return null;
        }
        Object value = row.get("_internal_id");
        return (value instanceof Number number) ? toBigInteger(number) : null;
    }

    private LocalDateTime timestamp(Map<String, Object> row, String key, LocalDateTime fallback) {
        String value = string(row, key);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
    }

    private byte[] bytes(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        return Base64.getDecoder().decode(String.valueOf(value));
    }

    private ULong toULong(BigInteger value) {
        return value == null ? null : ULong.valueOf(value);
    }

    private ULong nullableULong(BigInteger value) {
        return toULong(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BigInteger findId(Table table, TableField idField, Condition condition) {
        Record record = dslContext().select(idField).from(table).where(condition).limit(1).fetchOne();
        if (record == null) {
            return null;
        }
        return toBigInteger((Number) record.get(idField));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object findAny(Table table, Condition condition) {
        return dslContext().selectOne().from(table).where(condition).fetchOne();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String findString(Table table, TableField field, Condition condition) {
        Record record = dslContext().select(field).from(table).where(condition).limit(1).fetchOne();
        return (record != null) ? String.valueOf(record.get(field)) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean existsById(Table table, TableField idField, BigInteger id) {
        return dslContext().fetchExists(
                dslContext().selectOne().from(table).where(((Field<ULong>) idField).eq(toULong(id)))
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BigInteger insertRow(Table table, TableField idField, BigInteger internalIdHint, LinkedHashMap<Field<?>, Object> values) {
        InsertSetStep insert = dslContext().insertInto(table);
        BigInteger resolvedId;
        if (internalIdHint != null
                && internalIdHint.compareTo(END_USER_ID_FLOOR) < 0
                && !existsById(table, idField, internalIdHint)) {
            resolvedId = internalIdHint;
            reserveDeveloperId(table, resolvedId);
        } else {
            resolvedId = nextDeveloperId(table, idField);
        }
        values.put(idField, toULong(resolvedId));

        InsertSetMoreStep step = null;
        for (Map.Entry<Field<?>, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            step = (step == null) ?
                    insert.set((Field) entry.getKey(), entry.getValue()) :
                    step.set((Field) entry.getKey(), entry.getValue());
        }

        Record record = step.returning(idField).fetchOne();
        return toBigInteger((Number) record.get(idField));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BigInteger nextDeveloperId(Table table, TableField idField) {
        String tableName = table.getName();
        BigInteger nextDeveloperId = nextDeveloperIdByTable.get(tableName);
        if (nextDeveloperId == null) {
            Record record = dslContext().select(org.jooq.impl.DSL.max((Field<ULong>) idField).as("max_id"))
                    .from(table)
                    .where(((Field<ULong>) idField).lt(toULong(END_USER_ID_FLOOR)))
                    .fetchOne();
            ULong maxId = (record != null) ? record.get("max_id", ULong.class) : null;
            nextDeveloperId = (maxId != null) ? maxId.toBigInteger().add(BigInteger.ONE) : BigInteger.ONE;
        }
        if (nextDeveloperId.compareTo(END_USER_ID_FLOOR) >= 0) {
            throw new IllegalStateException("No developer ID is available for table '" + tableName + "'.");
        }
        nextDeveloperIdByTable.put(tableName, nextDeveloperId.add(BigInteger.ONE));
        return nextDeveloperId;
    }

    private void reserveDeveloperId(Table table, BigInteger id) {
        String tableName = table.getName();
        BigInteger nextDeveloperId = id.add(BigInteger.ONE);
        BigInteger cached = nextDeveloperIdByTable.get(tableName);
        if (cached == null || cached.compareTo(nextDeveloperId) < 0) {
            nextDeveloperIdByTable.put(tableName, nextDeveloperId);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void insertCompositeRow(Table table, LinkedHashMap<Field<?>, Object> values) {
        InsertSetStep insert = dslContext().insertInto(table);
        InsertSetMoreStep step = null;
        for (Map.Entry<Field<?>, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            step = (step == null) ?
                    insert.set((Field) entry.getKey(), entry.getValue()) :
                    step.set((Field) entry.getKey(), entry.getValue());
        }
        if (step != null) {
            step.execute();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void updateRowById(Table table, TableField idField, BigInteger id, LinkedHashMap<Field<?>, Object> values) {
        Query query = buildUpdateByIdQuery(table, idField, id, values);
        if (query != null) {
            query.execute();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Query buildUpdateByIdQuery(Table table, TableField idField, BigInteger id, LinkedHashMap<Field<?>, Object> values) {
        if (id == null || values == null || values.isEmpty()) {
            return null;
        }
        UpdateSetFirstStep update = dslContext().update(table);
        UpdateSetMoreStep step = null;
        for (Map.Entry<Field<?>, Object> entry : values.entrySet()) {
            step = (step == null) ?
                    update.set((Field) entry.getKey(), entry.getValue()) :
                    step.set((Field) entry.getKey(), entry.getValue());
        }
        return (step != null) ? step.where(((Field<ULong>) idField).eq(toULong(id))) : null;
    }

    private LinkedHashMap<Field<?>, Object> mapOf(Object... keyValues) {
        LinkedHashMap<Field<?>, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((Field<?>) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static final class ImportContext {
        private final ReleaseImport.Bundle bundle;
        private final Map<String, Map<String, BigInteger>> guidMaps = new HashMap<>();
        private final Map<String, Map<String, BigInteger>> keyMaps = new HashMap<>();
        private final Map<String, Map<String, BigInteger>> nameMaps = new HashMap<>();
        private final Map<String, Map<BigInteger, BigInteger>> internalMaps = new HashMap<>();
        private final Map<String, BigInteger> userIdByLoginId = new HashMap<>();
        private BigInteger libraryId;
        private BigInteger namespaceId;
        private BigInteger releaseId;
        private BigInteger workingReleaseId;

        private ImportContext(ReleaseImport.Bundle bundle) {
            this.bundle = bundle;
        }

        private ReleaseExport.Metadata metadata() {
            return bundle.metadata();
        }

        private boolean overwrite() {
            return bundle.overwrite();
        }

        private List<Map<String, Object>> rows(String table) {
            return bundle.payloadsByTable().getOrDefault(table, List.of());
        }

        private Map<String, Object> firstRow(String table) {
            List<Map<String, Object>> rows = rows(table);
            return rows.isEmpty() ? null : rows.get(0);
        }

        private Map<String, Object> releaseRow() {
            return firstRow("release");
        }

        private Map<String, BigInteger> idByGuid(String table) {
            return guidMaps.computeIfAbsent(table, key -> new HashMap<>());
        }

        private Map<String, BigInteger> idByKey(String table) {
            return keyMaps.computeIfAbsent(table, key -> new HashMap<>());
        }

        private Map<String, BigInteger> idByName(String table) {
            return nameMaps.computeIfAbsent(table, key -> new HashMap<>());
        }

        private Map<BigInteger, BigInteger> idByInternal(String table) {
            return internalMaps.computeIfAbsent(table, key -> new HashMap<>());
        }

        private String moduleSetReleaseKey(String moduleSetGuid, String name) {
            return moduleSetGuid + "|" + name;
        }

        private String moduleKey(String moduleSetGuid, String path) {
            return moduleSetGuid + "|" + path;
        }
    }
}
