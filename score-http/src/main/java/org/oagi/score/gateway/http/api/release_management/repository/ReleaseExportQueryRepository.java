package org.oagi.score.gateway.http.api.release_management.repository;

import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;

import java.util.List;
import java.util.Map;

public interface ReleaseExportQueryRepository {

    List<Map<String, Object>> getExportAccRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAccManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAsccRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAsccManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBccRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBccManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAsccpRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAsccpManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBccpRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBccpManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtScRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtScManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtAwdPriRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtScAwdPriRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportCodeListRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportCodeListManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportCodeListValueRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportCodeListValueManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAgencyIdListRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAgencyIdListManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAgencyIdListValueRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAgencyIdListValueManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportXbtRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportXbtManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportSeqKeyRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportReleaseDepRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportTagRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAccManifestTagRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportAsccpManifestTagRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBccpManifestTagRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportDtManifestTagRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleSetRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleSetReleaseRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleAccManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleAgencyIdListManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleAsccpManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleBccpManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleCodeListManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleDtManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleXbtManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBlobContentRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportBlobContentManifestRows(ReleaseId releaseId);

    List<Map<String, Object>> getExportModuleBlobContentManifestRows(ReleaseId releaseId);

}
