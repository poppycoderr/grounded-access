package io.groundedaccess.corpus;

import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.authorization.TextArrays;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes documents, versions and chunks. Reading chunks for retrieval is not allowed here; that goes through AuthorizedChunkQuery.
 */
@Repository
class CorpusWriter {

    private final JdbcClient jdbc;

    CorpusWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ActiveVersion(
            UUID documentId,

            int versionNo,

            String contentSha256,

            DocumentFormat format,

            String labelsSha256) {

        boolean hasContent(String sha, DocumentFormat submittedFormat) {
            return sha.equals(contentSha256) && format == submittedFormat;
        }

        boolean hasMetadata(SourceDocument source) {
            return metadataSha(source).equals(labelsSha256);
        }
    }

    /**
     * The document row held under a write lock, together with the version that was active when the lock was taken. A deleted document has no
     * current content, whatever its last version held.
     */
    record LockedDocument(
            UUID documentId,

            int versionNo,

            @Nullable String contentSha256,

            @Nullable DocumentFormat format,

            @Nullable String labelsSha256,

            @Nullable UUID activeVersionId,

            boolean deleted) {

        boolean hasContent(String sha, DocumentFormat submittedFormat) {
            return !deleted && sha.equals(contentSha256) && format == submittedFormat;
        }

        boolean hasMetadata(SourceDocument source) {
            return metadataSha(source).equals(labelsSha256);
        }
    }

    record NewVersion(
            String tenantId,

            SourceDocument source,

            String contentSha256,

            List<ChunkDraft> chunks,

            List<float[]> embeddings,

            String embeddingModel,

            String chunkerVersion) {
    }

    void ensureTenant(String tenantId) {
        jdbc.sql("insert into tenant (id, name) values (:id, :id) on conflict (id) do nothing").param("id", tenantId).update();
    }

    Optional<ActiveVersion> findActive(String tenantId, String key) {
        return jdbc.sql("""
                        select d.id, v.version_no, v.content_sha256, v.format, v.labels_sha256 || ':' || v.scope_sha256
                        from document d join document_version v on v.id = d.active_version_id
                        where d.tenant_id = :tenant and d.external_key = :key and d.status <> 'deleted'
                        """)
                .param("tenant", tenantId)
                .param("key", key)
                .query((rs, i) -> new ActiveVersion(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), DocumentFormat.fromColumn(rs.getString(4)),
                        rs.getString(5)))
                .optional();
    }

    /**
     * Creates the document if it is missing, locks its row for the rest of the transaction, and only then reads the active version. The two steps
     * must stay separate: after waiting for a lock, PostgreSQL re-evaluates the locked row but keeps the snapshot of anything joined to it, so a
     * single locking join would hand the waiting transaction a stale version number and it would try to write a version number that already exists.
     */
    LockedDocument lockDocument(String tenantId, String key) {
        jdbc.sql("insert into document (id, tenant_id, external_key) values (:id, :tenant, :key) on conflict (tenant_id, external_key) do nothing")
                .param("id", UUID.randomUUID())
                .param("tenant", tenantId)
                .param("key", key)
                .update();
        UUID documentId = jdbc.sql("select id from document where tenant_id = :tenant and external_key = :key for no key update")
                .param("tenant", tenantId)
                .param("key", key)
                .query(UUID.class)
                .single();
        return jdbc.sql("""
                        select d.last_version_no, v.content_sha256, v.format, v.labels_sha256 || ':' || v.scope_sha256, d.active_version_id, d.status = 'deleted'
                        from document d left join document_version v on v.id = d.active_version_id
                        where d.id = :id
                        """)
                .param("id", documentId)
                .query((rs, i) -> new LockedDocument(documentId, rs.getInt(1), rs.getString(2), format(rs.getString(3)), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getBoolean(6)))
                .single();
    }

    /**
     * Inserts the version and its chunks, then points the document at it. Must run in one transaction: the deferred foreign key on
     * active_version_id lets the pointer flip last, so readers see either the complete old version or the complete new one. A new version
     * brings a deleted document back, but never re-enables a disabled one: disabling is an administrator's decision that new content does not undo.
     */
    void writeVersion(NewVersion version, LockedDocument document) {
        UUID documentId = document.documentId();
        UUID versionId = UUID.randomUUID();
        AccessLabels labels = version.source().labels();
        DocumentScope scope = version.source().scope();
        jdbc.sql("""
                        insert into document_version (id, document_id, tenant_id, version_no, content_sha256, title, source_uri, format, chunker_version,
                            embedding_model, classification, allowed_departments, required_projects, labels_sha256, applies_to_regions, valid_from,
                            valid_to, scope_sha256)
                        values (:id, :document, :tenant, :versionNo, :sha, :title, :sourceUri, :format, :chunker, :model, :classification,
                            cast(:departments as text[]), cast(:projects as text[]), :labelsSha, cast(:regions as text[]),
                            cast(:validFrom as timestamptz), cast(:validTo as timestamptz), :scopeSha)
                        """)
                .param("id", versionId)
                .param("document", documentId)
                .param("tenant", version.tenantId())
                .param("versionNo", document.versionNo() + 1)
                .param("sha", version.contentSha256())
                .param("title", version.source().title())
                .param("sourceUri", version.source().sourceUri())
                .param("format", version.source().format().column())
                .param("chunker", version.chunkerVersion())
                .param("classification", labels.classification().column())
                .param("departments", TextArrays.literal(labels.allowedDepartments()))
                .param("projects", TextArrays.literal(labels.requiredProjects()))
                .param("labelsSha", labels.sha256())
                .param("regions", TextArrays.literal(scope.appliesToRegions()))
                .param("validFrom", timestamp(scope.validFrom()))
                .param("validTo", timestamp(scope.validTo()))
                .param("scopeSha", scope.sha256())
                .param("model", version.embeddingModel())
                .update();
        for (int i = 0; i < version.chunks().size(); i++) {
            ChunkDraft chunk = version.chunks().get(i);
            jdbc.sql("""
                            insert into chunk (id, tenant_id, document_id, version_id, ordinal, section_path, char_start, char_end, content, embedding, token_count)
                            values (:id, :tenant, :document, :version, :ordinal, :section, :start, :end, :content, cast(:embedding as vector), :tokens)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenant", version.tenantId())
                    .param("document", documentId)
                    .param("version", versionId)
                    .param("ordinal", chunk.ordinal())
                    .param("section", chunk.sectionPath())
                    .param("start", chunk.charStart())
                    .param("end", chunk.charEnd())
                    .param("content", chunk.content())
                    .param("embedding", Vectors.toLiteral(version.embeddings().get(i)))
                    .param("tokens", chunk.tokenCount())
                    .update();
        }
        jdbc.sql("""
                        update document
                        set active_version_id = :version, last_version_no = :versionNo,
                            status = case when status = 'deleted' then 'active' else status end, updated_at = now()
                        where id = :id
                        """)
                .param("version", versionId)
                .param("versionNo", document.versionNo() + 1)
                .param("id", documentId)
                .update();
    }

    /**
     * Gives the active content new labels or a new scope without touching the model service: a new version row takes over the chunks of the version it replaces,
     * and the pointer flips in the same transaction. Readers therefore see the old labels or the new ones, never chunks without a version. The
     * old version is left without chunks and is removed by cleanup. The caller must hold the document lock and have checked that the content is
     * unchanged.
     */
    void relabel(SourceDocument source, LockedDocument document) {
        UUID previous = Objects.requireNonNull(document.activeVersionId(), "a document with unchanged content has an active version");
        UUID versionId = UUID.randomUUID();
        AccessLabels labels = source.labels();
        DocumentScope scope = source.scope();
        jdbc.sql("""
                        insert into document_version (id, document_id, tenant_id, version_no, content_sha256, title, source_uri, format, chunker_version,
                            embedding_model, classification, allowed_departments, required_projects, labels_sha256, applies_to_regions, valid_from,
                            valid_to, scope_sha256)
                        select :id, document_id, tenant_id, :versionNo, content_sha256, :title, :sourceUri, format, chunker_version, embedding_model,
                            :classification, cast(:departments as text[]), cast(:projects as text[]), :labelsSha, cast(:regions as text[]),
                            cast(:validFrom as timestamptz), cast(:validTo as timestamptz), :scopeSha
                        from document_version where id = :previous
                        """)
                .param("id", versionId)
                .param("versionNo", document.versionNo() + 1)
                .param("title", source.title())
                .param("sourceUri", source.sourceUri())
                .param("classification", labels.classification().column())
                .param("departments", TextArrays.literal(labels.allowedDepartments()))
                .param("projects", TextArrays.literal(labels.requiredProjects()))
                .param("labelsSha", labels.sha256())
                .param("regions", TextArrays.literal(scope.appliesToRegions()))
                .param("validFrom", timestamp(scope.validFrom()))
                .param("validTo", timestamp(scope.validTo()))
                .param("scopeSha", scope.sha256())
                .param("previous", previous)
                .update();
        jdbc.sql("update chunk set version_id = :version where version_id = :previous").param("version", versionId).param("previous", previous).update();
        jdbc.sql("update document set active_version_id = :version, last_version_no = :versionNo, updated_at = now() where id = :id")
                .param("version", versionId)
                .param("versionNo", document.versionNo() + 1)
                .param("id", document.documentId())
                .update();
    }

    /**
     * Changes the status of a document that is not deleted. The update takes the same row lock as ingestion, so it waits for an ingestion of the
     * same key in flight and then applies on top of it.
     */
    Optional<DocumentState> changeStatus(String tenantId, String key, DocumentStatus status) {
        return jdbc.sql("""
                        update document d set status = :status, updated_at = now()
                        where d.tenant_id = :tenant and d.external_key = :key and d.status <> 'deleted'
                        returning d.external_key, d.status, coalesce((select v.version_no from document_version v where v.id = d.active_version_id), 0)
                        """)
                .param("status", status.column())
                .param("tenant", tenantId)
                .param("key", key)
                .query((rs, i) -> new DocumentState(rs.getString(1), DocumentStatus.fromColumn(rs.getString(2)), rs.getInt(3)))
                .optional();
    }

    /**
     * Deletes the versions of up to {@code batchSize} documents that no query can reach and returns how many were deleted; chunks cascade. Each
     * document is locked first, with the lock ingestion uses, and documents locked by an ingestion are skipped. Locking and deleting are separate
     * statements for the same reason as in {@link #lockDocument}: the later statements must see the rows as they are after the lock.
     */
    int purgeUnreachableVersions(int batchSize) {
        List<UUID> documents = jdbc.sql("""
                        select d.id from document d
                        where exists (
                            select 1 from document_version v
                            where v.document_id = d.id and (d.status = 'deleted' or v.id is distinct from d.active_version_id))
                        order by d.id
                        limit :batch
                        for no key update skip locked
                        """)
                .param("batch", batchSize)
                .query((rs, i) -> rs.getObject(1, UUID.class))
                .list();
        if (documents.isEmpty()) {
            return 0;
        }
        jdbc.sql("update document set active_version_id = null where id in (:ids) and status = 'deleted'").param("ids", documents).update();
        return jdbc.sql("""
                        delete from document_version v using document d
                        where v.document_id = d.id and d.id in (:ids) and v.id is distinct from d.active_version_id
                        """)
                .param("ids", documents)
                .update();
    }

    /**
     * Labels and scope are compared together: a change to either is a change of metadata, which creates a version without re-embedding.
     */
    private static String metadataSha(SourceDocument source) {
        return source.labels().sha256() + ":" + source.scope().sha256();
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable DocumentFormat format(@Nullable String column) {
        return column == null ? null : DocumentFormat.fromColumn(column);
    }
}
