package org.oagi.score.gateway.http.api.release_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.io.FileUtils;
import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.account_management.model.UserSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.CcDocument;
import org.oagi.score.gateway.http.api.cc_management.model.CcDocumentImpl;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.export.ExportContext;
import org.oagi.score.gateway.http.api.export.impl.ExportSchemaModuleVisitor;
import org.oagi.score.gateway.http.api.export.impl.JSONExportSchemaModuleVisitor;
import org.oagi.score.gateway.http.api.export.impl.StandaloneExportContextBuilder;
import org.oagi.score.gateway.http.api.export.impl.XMLExportSchemaModuleVisitor;
import org.oagi.score.gateway.http.api.export.model.JsonSchemaNamingStrategy;
import org.oagi.score.gateway.http.api.export.model.SchemaModule;
import org.oagi.score.gateway.http.api.export.model.SchemaNamingStrategy;
import org.oagi.score.gateway.http.api.export.model.XmlSchemaNamingStrategy;
import org.oagi.score.gateway.http.api.library_management.model.LibraryDetailsRecord;
import org.oagi.score.gateway.http.api.library_management.model.LibraryId;
import org.oagi.score.gateway.http.api.library_management.repository.LibraryQueryRepository;
import org.oagi.score.gateway.http.api.namespace_management.model.NamespaceDetailsRecord;
import org.oagi.score.gateway.http.api.namespace_management.model.NamespaceId;
import org.oagi.score.gateway.http.api.namespace_management.repository.NamespaceQueryRepository;
import org.oagi.score.gateway.http.api.release_management.controller.payload.ExportReleaseResponse;
import org.oagi.score.gateway.http.api.release_management.controller.payload.GenerateMigrationScriptResponse;
import org.oagi.score.gateway.http.api.release_management.model.*;
import org.oagi.score.gateway.http.api.release_management.repository.ReleaseQueryRepository;
import org.oagi.score.gateway.http.api.release_management.repository.criteria.ReleaseListFilterCriteria;
import org.oagi.score.gateway.http.common.model.*;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.common.util.Zip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.oagi.score.gateway.http.common.model.ScoreRole.DEVELOPER;

/**
 * Service class for querying release-related data.
 */
@Service
@Transactional(readOnly = true)
public class ReleaseQueryService {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final int MAX_ROWS_PER_PAYLOAD_FILE = 1000;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Autowired
    private RepositoryFactory repositoryFactory;

    private ReleaseQueryRepository query(ScoreUser requester) {
        return repositoryFactory.releaseQueryRepository(requester);
    }

    private LibraryQueryRepository libraryQuery(ScoreUser requester) {
        return repositoryFactory.libraryQueryRepository(requester);
    }

    private NamespaceQueryRepository namespaceQuery(ScoreUser requester) {
        return repositoryFactory.namespaceQueryRepository(requester);
    }

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private ResourceLoader resourceLoader;

    public List<ReleaseSummaryRecord> getReleaseSummaryList(
            ScoreUser requester, LibraryId libraryId, Collection<ReleaseState> releaseStateSet) {
        boolean isReadOnly = libraryQuery(requester).isReadOnly(libraryId);
        List<ReleaseSummaryRecord> releases = query(requester).getReleaseSummaryList(libraryId, releaseStateSet);

        ReleaseSummaryRecord workingRelease = releases.stream()
                .filter(ReleaseSummaryRecord::isWorkingRelease)
                .findAny()
                .orElse(null);
        if (workingRelease != null) {
            releases.remove(workingRelease);

            if (!isReadOnly) {
                if (requester.hasRole(DEVELOPER)) {
                    releases.add(0, workingRelease);
                } else {
                    releases.add(workingRelease);
                }
            }
        }

        return releases;
    }

    /**
     * Retrieves a paginated list of releases based on filter criteria.
     *
     * @param requester      The user making the request.
     * @param filterCriteria The criteria to filter the releases.
     * @param pageRequest    The pagination information.
     * @return A {@link ResultAndCount} object containing the list of releases and the total count.
     */
    public ResultAndCount<ReleaseListEntryRecord> getReleaseList(ScoreUser requester,
                                                                 ReleaseListFilterCriteria filterCriteria,
                                                                 PageRequest pageRequest) {
        return query(requester).getReleaseList(filterCriteria, pageRequest);
    }

    /**
     * Retrieves the details of a specific release by its ID.
     *
     * @param requester The user making the request.
     * @param releaseId The ID of the release to fetch details for.
     * @return The {@link ReleaseDetailsRecord} containing the release details.
     */
    public ReleaseDetailsRecord getReleaseDetails(ScoreUser requester, ReleaseId releaseId) {
        return query(requester).getReleaseDetails(releaseId);
    }

    public AssignComponents getAssignComponents(ScoreUser requester, ReleaseId releaseId) {
        return query(requester).getAssignComponents(releaseId);
    }

    public GenerateMigrationScriptResponse generateMigrationScript(ScoreUser requester, ReleaseId releaseId) throws IOException {
        ReleaseSummaryRecord release = query(requester).getReleaseSummary(releaseId);

        MigrationScriptGenerator generator = new MigrationScriptGenerator(dslContext, resourceLoader,
                BigInteger.valueOf(100000000L));
        File file = generator.generate(requester, release);

        String fileName = release.releaseNum().replace(".", "_") + ".zip";
        return new GenerateMigrationScriptResponse(fileName, file);
    }

    public ExportReleaseResponse exportRelease(ScoreUser requester, ReleaseId releaseId) throws IOException {
        ReleaseDetailsRecord release = query(requester).getReleaseDetails(releaseId);
        LibraryDetailsRecord library = libraryQuery(requester).getLibraryDetails(release.libraryId());
        NamespaceDetailsRecord namespace = getNamespaceDetails(requester, release.namespaceId());
        ReleaseExport.LibraryKey libraryKey = new ReleaseExport.LibraryKey(
                library.name(), library.organization(), library.type(), library.domain());
        var releaseExportQueryRepository = repositoryFactory.releaseExportQueryRepository(requester);

        File baseDir = new File(FileUtils.getTempDirectory(), UUID.randomUUID().toString());
        FileUtils.forceMkdir(baseDir);
        try {
            File metadataFile = new File(baseDir, "metadata.json");
            List<ReleaseExport.PayloadFile> payloadFiles = new ArrayList<>();
            List<File> generatedFiles = new ArrayList<>();
            Set<String> loginIds = new LinkedHashSet<>();
            addPayloadFiles(baseDir, payloadFiles, generatedFiles,
                    "release", "release", List.of(toExportReleaseRow(requester, release)));
            addPayloadFiles(baseDir, payloadFiles, generatedFiles,
                    "library", "library", List.of(toExportLibraryRow(library)));
            if (namespace != null) {
                addPayloadFiles(baseDir, payloadFiles, generatedFiles,
                        "namespace", "namespace", List.of(toExportNamespaceRow(namespace)));
            }

            loginIds.add(toLoginId(release.created().who()));
            loginIds.add(toLoginId(release.lastUpdated().who()));

            List<Map<String, Object>> accRows = releaseExportQueryRepository.getExportAccRows(releaseId);
            collectLoginIds(loginIds, accRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "acc", "acc", accRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "acc_manifest", "acc-manifest", releaseExportQueryRepository.getExportAccManifestRows(releaseId));

            List<Map<String, Object>> asccpRows = releaseExportQueryRepository.getExportAsccpRows(releaseId);
            collectLoginIds(loginIds, asccpRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "asccp", "asccp", asccpRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "asccp_manifest", "asccp-manifest", releaseExportQueryRepository.getExportAsccpManifestRows(releaseId));

            List<Map<String, Object>> bccpRows = releaseExportQueryRepository.getExportBccpRows(releaseId);
            collectLoginIds(loginIds, bccpRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "bccp", "bccp", bccpRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "bccp_manifest", "bccp-manifest", releaseExportQueryRepository.getExportBccpManifestRows(releaseId));

            List<Map<String, Object>> asccRows = releaseExportQueryRepository.getExportAsccRows(releaseId);
            collectLoginIds(loginIds, asccRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "ascc", "ascc", asccRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "ascc_manifest", "ascc-manifest", releaseExportQueryRepository.getExportAsccManifestRows(releaseId));

            List<Map<String, Object>> bccRows = releaseExportQueryRepository.getExportBccRows(releaseId);
            collectLoginIds(loginIds, bccRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "bcc", "bcc", bccRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "bcc_manifest", "bcc-manifest", releaseExportQueryRepository.getExportBccManifestRows(releaseId));

            List<Map<String, Object>> dtRows = releaseExportQueryRepository.getExportDtRows(releaseId);
            collectLoginIds(loginIds, dtRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt", "dt", dtRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt_manifest", "dt-manifest", releaseExportQueryRepository.getExportDtManifestRows(releaseId));

            List<Map<String, Object>> dtScRows = releaseExportQueryRepository.getExportDtScRows(releaseId);
            collectLoginIds(loginIds, dtScRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt_sc", "dt-sc", dtScRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt_sc_manifest", "dt-sc-manifest", releaseExportQueryRepository.getExportDtScManifestRows(releaseId));

            List<Map<String, Object>> codeListRows = releaseExportQueryRepository.getExportCodeListRows(releaseId);
            collectLoginIds(loginIds, codeListRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "code_list", "code-list", codeListRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "code_list_manifest", "code-list-manifest", releaseExportQueryRepository.getExportCodeListManifestRows(releaseId));
            List<Map<String, Object>> codeListValueRows = releaseExportQueryRepository.getExportCodeListValueRows(releaseId);
            collectLoginIds(loginIds, codeListValueRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "code_list_value", "code-list-value", codeListValueRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "code_list_value_manifest", "code-list-value-manifest", releaseExportQueryRepository.getExportCodeListValueManifestRows(releaseId));

            List<Map<String, Object>> agencyIdListRows = releaseExportQueryRepository.getExportAgencyIdListRows(releaseId);
            collectLoginIds(loginIds, agencyIdListRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "agency_id_list", "agency-id-list", agencyIdListRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "agency_id_list_manifest", "agency-id-list-manifest", releaseExportQueryRepository.getExportAgencyIdListManifestRows(releaseId));
            List<Map<String, Object>> agencyIdListValueRows = releaseExportQueryRepository.getExportAgencyIdListValueRows(releaseId);
            collectLoginIds(loginIds, agencyIdListValueRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "agency_id_list_value", "agency-id-list-value", agencyIdListValueRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "agency_id_list_value_manifest", "agency-id-list-value-manifest", releaseExportQueryRepository.getExportAgencyIdListValueManifestRows(releaseId));

            List<Map<String, Object>> xbtRows = releaseExportQueryRepository.getExportXbtRows(releaseId);
            collectLoginIds(loginIds, xbtRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "xbt", "xbt", xbtRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "xbt_manifest", "xbt-manifest", releaseExportQueryRepository.getExportXbtManifestRows(releaseId));

            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "seq_key", "seq-key", releaseExportQueryRepository.getExportSeqKeyRows(releaseId));
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt_awd_pri", "dt-awd-pri", releaseExportQueryRepository.getExportDtAwdPriRows(releaseId));
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt_sc_awd_pri", "dt-sc-awd-pri", releaseExportQueryRepository.getExportDtScAwdPriRows(releaseId));

            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "release_dep", "release-dep", releaseExportQueryRepository.getExportReleaseDepRows(releaseId));

            List<Map<String, Object>> tagRows = releaseExportQueryRepository.getExportTagRows(releaseId);
            collectLoginIds(loginIds, tagRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "tag", "tag", tagRows);
            List<Map<String, Object>> accManifestTagRows = releaseExportQueryRepository.getExportAccManifestTagRows(releaseId);
            collectLoginIds(loginIds, accManifestTagRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "acc_manifest_tag", "acc-manifest-tag", accManifestTagRows);
            List<Map<String, Object>> asccpManifestTagRows = releaseExportQueryRepository.getExportAsccpManifestTagRows(releaseId);
            collectLoginIds(loginIds, asccpManifestTagRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "asccp_manifest_tag", "asccp-manifest-tag", asccpManifestTagRows);
            List<Map<String, Object>> bccpManifestTagRows = releaseExportQueryRepository.getExportBccpManifestTagRows(releaseId);
            collectLoginIds(loginIds, bccpManifestTagRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "bccp_manifest_tag", "bccp-manifest-tag", bccpManifestTagRows);
            List<Map<String, Object>> dtManifestTagRows = releaseExportQueryRepository.getExportDtManifestTagRows(releaseId);
            collectLoginIds(loginIds, dtManifestTagRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "dt_manifest_tag", "dt-manifest-tag", dtManifestTagRows);

            List<Map<String, Object>> moduleSetRows = releaseExportQueryRepository.getExportModuleSetRows(releaseId);
            collectLoginIds(loginIds, moduleSetRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_set", "module-set", moduleSetRows);

            List<Map<String, Object>> moduleSetReleaseRows = releaseExportQueryRepository.getExportModuleSetReleaseRows(releaseId);
            collectLoginIds(loginIds, moduleSetReleaseRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_set_release", "module-set-release", moduleSetReleaseRows);

            List<Map<String, Object>> moduleRows = releaseExportQueryRepository.getExportModuleRows(releaseId);
            collectLoginIds(loginIds, moduleRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module", "module", moduleRows);

            List<Map<String, Object>> moduleAccManifestRows = releaseExportQueryRepository.getExportModuleAccManifestRows(releaseId);
            collectLoginIds(loginIds, moduleAccManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_acc_manifest", "module-acc-manifest", moduleAccManifestRows);
            List<Map<String, Object>> moduleAgencyIdListManifestRows = releaseExportQueryRepository.getExportModuleAgencyIdListManifestRows(releaseId);
            collectLoginIds(loginIds, moduleAgencyIdListManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_agency_id_list_manifest", "module-agency-id-list-manifest", moduleAgencyIdListManifestRows);
            List<Map<String, Object>> moduleAsccpManifestRows = releaseExportQueryRepository.getExportModuleAsccpManifestRows(releaseId);
            collectLoginIds(loginIds, moduleAsccpManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_asccp_manifest", "module-asccp-manifest", moduleAsccpManifestRows);
            List<Map<String, Object>> moduleBccpManifestRows = releaseExportQueryRepository.getExportModuleBccpManifestRows(releaseId);
            collectLoginIds(loginIds, moduleBccpManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_bccp_manifest", "module-bccp-manifest", moduleBccpManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "blob_content", "blob-content", releaseExportQueryRepository.getExportBlobContentRows(releaseId));
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "blob_content_manifest", "blob-content-manifest", releaseExportQueryRepository.getExportBlobContentManifestRows(releaseId));
            List<Map<String, Object>> moduleBlobContentManifestRows = releaseExportQueryRepository.getExportModuleBlobContentManifestRows(releaseId);
            collectLoginIds(loginIds, moduleBlobContentManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_blob_content_manifest", "module-blob-content-manifest", moduleBlobContentManifestRows);
            List<Map<String, Object>> moduleCodeListManifestRows = releaseExportQueryRepository.getExportModuleCodeListManifestRows(releaseId);
            collectLoginIds(loginIds, moduleCodeListManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_code_list_manifest", "module-code-list-manifest", moduleCodeListManifestRows);
            List<Map<String, Object>> moduleDtManifestRows = releaseExportQueryRepository.getExportModuleDtManifestRows(releaseId);
            collectLoginIds(loginIds, moduleDtManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_dt_manifest", "module-dt-manifest", moduleDtManifestRows);
            List<Map<String, Object>> moduleXbtManifestRows = releaseExportQueryRepository.getExportModuleXbtManifestRows(releaseId);
            collectLoginIds(loginIds, moduleXbtManifestRows);
            addPayloadFiles(baseDir, payloadFiles, generatedFiles, "module_xbt_manifest", "module-xbt-manifest", moduleXbtManifestRows);

            addPayloadFiles(baseDir, payloadFiles, generatedFiles,
                    "app_user", "app-user", toExportAppUserRows(requester, loginIds));

            ReleaseExport.Metadata metadata = new ReleaseExport.Metadata(
                    "score-release-export",
                    "1.0.0-draft-01",
                    java.time.Instant.now().toString(),
                    new ReleaseExport.Source(
                            "connectCenter",
                            libraryKey,
                            toExportReleaseHeader(requester, release, libraryKey)
                    ),
                    payloadFiles
            );
            objectMapper.writeValue(metadataFile, metadata);

            String baseName = sanitizeFileName(release.releaseNum()) + ".release-export";
            List<File> bundleFiles = new ArrayList<>();
            bundleFiles.add(metadataFile);
            bundleFiles.addAll(generatedFiles);
            File file = Zip.compression(bundleFiles, baseName);
            return new ExportReleaseResponse(baseName + ".zip", file);
        } finally {
            FileUtils.deleteDirectory(baseDir);
        }
    }

    public ExportStandaloneSchemaResponse exportStandaloneSchema(
            ScoreUser requester, Collection<AsccpManifestId> asccpManifestIdList) throws Exception {
        return exportStandaloneSchema(requester, asccpManifestIdList, "XML", null);
    }

    public ExportStandaloneSchemaResponse exportStandaloneSchema(
            ScoreUser requester, Collection<AsccpManifestId> asccpManifestIdList,
            String expressionOption, String expressionVersion) throws Exception {
        if (asccpManifestIdList == null || asccpManifestIdList.isEmpty()) {
            throw new IllegalArgumentException();
        }

        String normalizedExpressionOption = normalizeExpressionOption(expressionOption);
        validateExpressionVersion(normalizedExpressionOption, expressionVersion);

        File baseDir = new File(FileUtils.getTempDirectory(), UUID.randomUUID().toString());
        FileUtils.forceMkdir(baseDir);

        try {
            List<File> files = Collections.synchronizedList(new ArrayList<>());

            Map<AsccpManifestId, ReleaseId> releaseIdMap = getReleaseIdMapByAsccpManifestIdList(requester, asccpManifestIdList);
            CcDocument ccDocument = new CcDocumentImpl(requester, repositoryFactory, releaseIdMap.values());
            Map<String, Integer> pathCounter = new ConcurrentHashMap<>();
            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
            asccpManifestIdList.parallelStream().forEach(asccpManifestId -> {
                try {
                    SchemaNamingStrategy namingStrategy = newSchemaNamingStrategy(normalizedExpressionOption);
                    ExportSchemaModuleVisitor visitor = newSchemaModuleVisitor(ccDocument, normalizedExpressionOption);
                    visitor.setBaseDirectory(baseDir);

                    StandaloneExportContextBuilder builder =
                            new StandaloneExportContextBuilder(ccDocument, pathCounter, namingStrategy);
                    ExportContext exportContext = builder.build(asccpManifestId);

                    for (SchemaModule schemaModule : exportContext.getSchemaModules()) {
                        schemaModule.visit(visitor);
                        File file = schemaModule.getModuleFile();
                        if (file != null) {
                            files.add(file);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Unexpected error occurs while it generates a stand-alone schema for 'asccp_manifest_id' [" + asccpManifestId + "]", e);
                    exceptions.add(e);
                }
            });

            if (!exceptions.isEmpty()) {
                throw new IllegalStateException(exceptions.stream().map(e -> e.getMessage()).collect(Collectors.joining("\n")));
            }

            if (files.size() == 1) {
                File srcFile = files.get(0);
                File destFile = File.createTempFile("oagis-", null);
                if (!srcFile.renameTo(destFile)) {
                    FileUtils.copyFile(srcFile, destFile);
                }
                String filename = srcFile.getName();
                return new ExportStandaloneSchemaResponse(filename, destFile);
            } else {
                return new ExportStandaloneSchemaResponse(UUID.randomUUID() + ".zip",
                        Zip.compressionHierarchy(baseDir, files));
            }
        } finally {
            FileUtils.deleteDirectory(baseDir);
        }
    }

    public Map<AsccpManifestId, ReleaseId> getReleaseIdMapByAsccpManifestIdList(
            ScoreUser requester, Collection<AsccpManifestId> asccpManifestIdList) {
        return query(requester).getReleaseIdMapByAsccpManifestIdList(asccpManifestIdList);
    }

    private String normalizeExpressionOption(String expressionOption) {
        if (!org.springframework.util.StringUtils.hasLength(expressionOption)) {
            return "XML";
        }
        String normalized = expressionOption.trim().toUpperCase();
        if ("XML".equals(normalized) || "JSON".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported expression option: " + expressionOption);
    }

    private ExportSchemaModuleVisitor newSchemaModuleVisitor(CcDocument ccDocument, String expressionOption) {
        if ("JSON".equals(expressionOption)) {
            return new JSONExportSchemaModuleVisitor(ccDocument, new JsonSchemaNamingStrategy());
        }
        return new XMLExportSchemaModuleVisitor(ccDocument, new XmlSchemaNamingStrategy());
    }

    private SchemaNamingStrategy newSchemaNamingStrategy(String expressionOption) {
        if ("JSON".equals(expressionOption)) {
            return new JsonSchemaNamingStrategy();
        }
        return new XmlSchemaNamingStrategy();
    }

    private void validateExpressionVersion(String expressionOption, String expressionVersion) {
        if (!"JSON".equals(expressionOption)) {
            return;
        }
        if (!org.springframework.util.StringUtils.hasLength(expressionVersion)) {
            return;
        }
        String normalizedVersion = expressionVersion.trim().toUpperCase();
        if (!"2020-12".equals(normalizedVersion) && !"202012".equals(normalizedVersion)) {
            throw new IllegalArgumentException("Unsupported JSON expression version: " + expressionVersion);
        }
    }

    private String standaloneSchemaFilenameBase(
            CcDocument ccDocument, Map<String, Integer> pathCounter, AsccpManifestId asccpManifestId) {
        var asccp = ccDocument.getAsccp(asccpManifestId);
        AccSummaryRecord roleOfAcc = ccDocument.getAcc(asccp.roleOfAccManifestId());

        String term;
        if (asccp.propertyTerm().equals(roleOfAcc.objectClassTerm())) {
            term = asccp.propertyTerm();
        } else {
            term = asccp.propertyTerm() + roleOfAcc.objectClassTerm();
        }
        String path = term.replaceAll(" ", "").replace("Identifier", "ID");
        synchronized (pathCounter) {
            int count = pathCounter.getOrDefault(path, 0);
            if (count > 0) {
                path = path + "_" + count;
            }
            pathCounter.put(path, count + 1);
        }
        return path;
    }

    public String generatePlantUmlText(
            ScoreUser requester, ReleaseId releaseId, String releaseLinkTemplate, String libraryLinkTemplate) {

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("!pragma layout smetana\n");
        sb.append("skinparam svgLinkTarget _blank\n");
        sb.append("set namespaceSeparator none\n");
        String styleName = "link_style";
        sb.append("<style>\n")
                .append("\t").append("classDiagram {\n")
                .append("\t\t").append("class {\n")
                .append("\t\t\t").append("header {\n")
                .append("\t\t\t\t").append(".").append(styleName).append(" {\n")
                .append("\t\t\t\t\t").append("FontColor blue\n")
                .append("\t\t\t\t}\n")
                .append("\t\t\t}\n")
                .append("\t\t}\n")
                .append("\t}\n")
                .append("</style>\n");
        sb.append("\n");

        var query = query(requester);
        ReleaseSummaryRecord release = query.getReleaseSummary(releaseId);
        List<ReleaseSummaryRecord> dependentList = query.getDependentReleaseSummaryList(releaseId);

        sb.append(
                toClassDiagram(requester, release, styleName, releaseLinkTemplate, libraryLinkTemplate)
        ).append("\n");

        for (ReleaseSummaryRecord dependent : dependentList) {
            sb.append(
                    toClassDiagram(requester, dependent, styleName, releaseLinkTemplate, libraryLinkTemplate)
            ).append("\n");

            sb.append("\"Release " + release.releaseNum() + "\" o-- \"Release " + dependent.releaseNum() + "\"\n");
        }

        sb.append("\n");
        sb.append("hide circle\n");
        sb.append("hide empty members\n");
        sb.append("hide <<").append(styleName).append(">> stereotype\n");
        sb.append("@enduml");

        return sb.toString();
    }

    private String toClassDiagram(ScoreUser requester,
                                  ReleaseSummaryRecord release,
                                  String styleName,
                                  String releaseLinkTemplate,
                                  String libraryLinkTemplate) {

        var libraryQuery = repositoryFactory.libraryQueryRepository(requester);
        LibraryDetailsRecord library = libraryQuery.getLibraryDetails(release.libraryId());

        StringBuilder sb = new StringBuilder();

        sb.append("class \"Release " + release.releaseNum() + "\" ")
                .append("<<").append(styleName).append(">> [[")
                .append(releaseLinkTemplate.replaceAll("\\{releaseId\\}", release.releaseId().toString()))
                .append("]] {\n");

        sb.append("\t +library: \"" + library.name() + "\" [[[")
                .append(libraryLinkTemplate.replaceAll("\\{libraryId\\}", library.libraryId().toString()))
                .append("]]]\n");

        sb.append("}").append("\n");

        return sb.toString();
    }

    private NamespaceDetailsRecord getNamespaceDetails(ScoreUser requester, NamespaceId namespaceId) {
        if (namespaceId == null) {
            return null;
        }
        return namespaceQuery(requester).getNamespaceDetails(namespaceId, requester.userId());
    }

    private ReleaseExport.LibraryRow toExportLibraryRow(LibraryDetailsRecord library) {
        return new ReleaseExport.LibraryRow(
                library.libraryId().value(),
                library.name(),
                library.organization(),
                library.type(),
                library.domain(),
                library.description(),
                library.link(),
                library.state(),
                library.readOnly(),
                library.isDefault()
        );
    }

    private ReleaseExport.NamespaceRow toExportNamespaceRow(NamespaceDetailsRecord namespace) {
        if (namespace == null) {
            return null;
        }

        return new ReleaseExport.NamespaceRow(
                namespace.namespaceId().value(),
                namespace.uri(),
                namespace.prefix(),
                namespace.description(),
                namespace.standard()
        );
    }

    private ReleaseExport.ReleaseRow toExportReleaseRow(ScoreUser requester, ReleaseDetailsRecord release) {
        return new ReleaseExport.ReleaseRow(
                release.releaseId().value(),
                release.guid().value(),
                release.releaseNum(),
                release.state().name(),
                release.releaseNote(),
                release.releaseLicense(),
                release.namespaceId() != null ?
                        getNamespaceDetails(requester, release.namespaceId()).uri() :
                        null,
                toLoginId(release.created().who()),
                toLoginId(release.lastUpdated().who()),
                toTimestamp(release.created()),
                toTimestamp(release.lastUpdated())
        );
    }

    private ReleaseExport.ReleaseHeader toExportReleaseHeader(
            ScoreUser requester,
            ReleaseDetailsRecord release,
            ReleaseExport.LibraryKey libraryKey) {
        return new ReleaseExport.ReleaseHeader(
                release.guid().value(),
                release.releaseNum(),
                libraryKey,
                toReleaseRef(requester, release.prev())
        );
    }

    private ReleaseExport.ReleaseRef toReleaseRef(ScoreUser requester, ReleaseSummaryRecord releaseSummary) {
        if (releaseSummary == null) {
            return null;
        }

        ReleaseDetailsRecord releaseDetails = query(requester).getReleaseDetails(releaseSummary.releaseId());
        return new ReleaseExport.ReleaseRef(
                releaseDetails.guid().value(),
                releaseSummary.releaseNum()
        );
    }

    private String toLoginId(UserSummaryRecord user) {
        return user != null ? user.loginId() : null;
    }

    private String toTimestamp(WhoAndWhen whoAndWhen) {
        if (whoAndWhen == null || whoAndWhen.when() == null) {
            return null;
        }
        return whoAndWhen.when().toInstant().toString();
    }

    private <T> void addPayloadFiles(
            File baseDir,
            List<ReleaseExport.PayloadFile> payloadFiles,
            List<File> generatedFiles,
            String table,
            String fileStem,
            List<T> rows) throws IOException {
        List<File> files = writePayloadFiles(baseDir, fileStem, rows);
        if (files.isEmpty()) {
            return;
        }
        generatedFiles.addAll(files);
        payloadFiles.add(new ReleaseExport.PayloadFile(
                table,
                files.stream().map(File::getName).toList()
        ));
    }

    private void collectLoginIds(Set<String> loginIds, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().endsWith("_login_id") && entry.getValue() instanceof String loginId && !loginId.isEmpty()) {
                    loginIds.add(loginId);
                }
            }
        }
    }

    private List<ReleaseExport.AppUserRow> toExportAppUserRows(ScoreUser requester, Set<String> loginIds) {
        if (loginIds == null || loginIds.isEmpty()) {
            return List.of();
        }
        return repositoryFactory.accountQueryRepository(requester).getAccountDetailsListByLoginIds(loginIds).stream()
                .map(account -> new ReleaseExport.AppUserRow(
                        account.userId().value(),
                        account.loginId(),
                        account.username(),
                        toRoles(account.developer(), account.admin())
                ))
                .toList();
    }

    private List<String> toRoles(Boolean isDeveloper, Boolean isAdmin) {
        List<String> roles = new ArrayList<>();
        if (Boolean.TRUE.equals(isDeveloper)) {
            roles.add("developer");
        }
        if (Boolean.TRUE.equals(isAdmin)) {
            roles.add("admin");
        }
        return roles;
    }

    private List<String> toRoles(Number isDeveloper, Number isAdmin) {
        return toRoles(isTrue(isDeveloper), isTrue(isAdmin));
    }

    private Boolean isTrue(Number value) {
        return value != null && value.intValue() != 0;
    }

    private <T> List<File> writePayloadFiles(File baseDir, String tableName, List<T> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<File> files = new ArrayList<>();
        int chunkCount = (rows.size() + MAX_ROWS_PER_PAYLOAD_FILE - 1) / MAX_ROWS_PER_PAYLOAD_FILE;
        for (int i = 0; i < chunkCount; i++) {
            int fromIndex = i * MAX_ROWS_PER_PAYLOAD_FILE;
            int toIndex = Math.min(rows.size(), fromIndex + MAX_ROWS_PER_PAYLOAD_FILE);
            List<T> chunk = rows.subList(fromIndex, toIndex);
            String filename = (chunkCount == 1) ?
                    "payload-" + tableName + ".json" :
                    String.format("payload-%s-%02d.json", tableName, i + 1);
            File file = new File(baseDir, filename);
            objectMapper.writeValue(file, chunk);
            files.add(file);
        }
        return files;
    }

    private String sanitizeFileName(String releaseNum) {
        return releaseNum.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
