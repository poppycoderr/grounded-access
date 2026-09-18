package io.groundedaccess.corpus;

import java.util.List;
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

            String contentSha256) {
    }

    record NewVersion(
            String tenantId,

            SourceDocument source,

            String contentSha256,

            List<ChunkDraft> chunks,

            List<float[]> embeddings,

            String embeddingModel) {
    }

    void ensureTenant(String tenantId) {
        jdbc.sql("insert into tenant (id, name) values (:id, :id) on conflict (id) do nothing").param("id", tenantId).update();
    }

    Optional<ActiveVersion> findActive(String tenantId, String key) {
        return jdbc.sql("""
                        select d.id, v.version_no, v.content_sha256
                        from document d join document_version v on v.id = d.active_version_id
                        where d.tenant_id = :tenant and d.external_key = :key
                        """)
                .param("tenant", tenantId)
                .param("key", key)
                .query((rs, i) -> new ActiveVersion(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)))
                .optional();
    }

    /**
     * Inserts the version and its chunks, then points the document at it. Must run in one transaction: the deferred foreign key on
     * active_version_id lets the pointer flip last, so readers see either the complete old version or the complete new one.
     */
    void writeVersion(NewVersion version, @Nullable ActiveVersion previous) {
        UUID documentId = previous != null ? previous.documentId() : UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        if (previous == null) {
            jdbc.sql("insert into document (id, tenant_id, external_key) values (:id, :tenant, :key)")
                    .param("id", documentId)
                    .param("tenant", version.tenantId())
                    .param("key", version.source().key())
                    .update();
        }
        jdbc.sql("""
                        insert into document_version (id, document_id, tenant_id, version_no, content_sha256, title, source_uri, chunker_version, embedding_model)
                        values (:id, :document, :tenant, :versionNo, :sha, :title, :sourceUri, :chunker, :model)
                        """)
                .param("id", versionId)
                .param("document", documentId)
                .param("tenant", version.tenantId())
                .param("versionNo", previous == null ? 1 : previous.versionNo() + 1)
                .param("sha", version.contentSha256())
                .param("title", version.source().title())
                .param("sourceUri", version.source().sourceUri())
                .param("chunker", MarkdownChunker.VERSION)
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
        jdbc.sql("update document set active_version_id = :version, status = 'active', updated_at = now() where id = :id")
                .param("version", versionId)
                .param("id", documentId)
                .update();
    }
}
