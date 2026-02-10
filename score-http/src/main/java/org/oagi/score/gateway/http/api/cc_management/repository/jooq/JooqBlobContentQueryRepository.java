package org.oagi.score.gateway.http.api.cc_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.SelectJoinStep;
import org.oagi.score.gateway.http.api.cc_management.model.blob_content.BlobContentId;
import org.oagi.score.gateway.http.api.cc_management.model.blob_content.BlobContentManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.blob_content.BlobContentSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.repository.BlobContentQueryRepository;
import org.oagi.score.gateway.http.api.library_management.model.LibrarySummaryRecord;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseSummaryRecord;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.*;

public class JooqBlobContentQueryRepository extends JooqBaseRepository implements BlobContentQueryRepository {

    public JooqBlobContentQueryRepository(DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, requester, repositoryFactory);
    }

    @Override
    public List<BlobContentSummaryRecord> getBlobContentSummaryList(Collection<ReleaseId> releaseIdList) {
        if (releaseIdList == null || releaseIdList.isEmpty()) {
            return Collections.emptyList();
        }

        var queryBuilder = new GetBlobContentSummaryQueryBuilder();
        return queryBuilder.select()
                .where(BLOB_CONTENT_MANIFEST.RELEASE_ID.in(valueOf(releaseIdList)))
                .fetch(queryBuilder.mapper());
    }

    private class GetBlobContentSummaryQueryBuilder {

        SelectJoinStep<? extends Record> select() {
            return dslContext().select(concat(fields(
                            BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID,
                            BLOB_CONTENT.BLOB_CONTENT_ID,
                            BLOB_CONTENT.CONTENT,

                            RELEASE.RELEASE_ID,
                            RELEASE.RELEASE_NUM,
                            RELEASE.STATE.as("release_state")
                    ), libraryFields()))
                    .from(BLOB_CONTENT_MANIFEST)
                    .join(BLOB_CONTENT).on(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_ID.eq(BLOB_CONTENT.BLOB_CONTENT_ID))
                    .join(RELEASE).on(BLOB_CONTENT_MANIFEST.RELEASE_ID.eq(RELEASE.RELEASE_ID))
                    .join(LIBRARY).on(RELEASE.LIBRARY_ID.eq(LIBRARY.LIBRARY_ID));
        }

        private RecordMapper<Record, BlobContentSummaryRecord> mapper() {
            return record -> {
                BlobContentManifestId blobContentManifestId =
                        new BlobContentManifestId(record.get(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID).toBigInteger());
                BlobContentId blobContentId =
                        new BlobContentId(record.get(BLOB_CONTENT.BLOB_CONTENT_ID).toBigInteger());
                LibrarySummaryRecord library = fetchLibrarySummary(record);
                ReleaseSummaryRecord release = fetchReleaseSummary(record);
                return new BlobContentSummaryRecord(
                        library, release,

                        blobContentManifestId,
                        blobContentId,
                        record.get(BLOB_CONTENT.CONTENT)
                );
            };
        }
    }

}
