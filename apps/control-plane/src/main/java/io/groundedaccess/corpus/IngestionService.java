package io.groundedaccess.corpus;

import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingests documents into one tenant. A document whose normalized content is unchanged is skipped; otherwise it gets a new version. Embedding
 * happens before the transaction so no database connection is held during model calls, and the write decision is repeated under a document lock so
 * concurrent ingestions of the same key stay consistent.
 */
@Service
public class IngestionService {

    private final CorpusWriter writer;

    private final EmbeddingClient embeddings;

    private final TransactionTemplate transactions;

    private final DocumentChunker chunker;

    public IngestionService(CorpusWriter writer, EmbeddingClient embeddings, TransactionTemplate transactions,
            @Value("${ga.corpus.chunk-max-words:180}") int chunkMaxWords, @Value("${ga.corpus.chunk-overlap-words:30}") int chunkOverlapWords) {
        this.writer = writer;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.chunker = new DocumentChunker(chunkMaxWords, chunkOverlapWords);
    }

    public IngestionResult ingest(String tenantId, List<SourceDocument> documents) {
        writer.ensureTenant(tenantId);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int chunkCount = 0;
        for (SourceDocument source : documents) {
            String normalized = DocumentChunker.normalize(source.content());
            String sha = sha256(normalized);
            Optional<CorpusWriter.ActiveVersion> active = writer.findActive(tenantId, source.key()).filter(a -> a.hasContent(sha, source.format()));
            if (active.isPresent()) {
                if (active.get().hasLabels(source.labels())) {
                    unchanged++;
                    continue;
                }
                Outcome relabelled = transactions.execute(status -> relabel(tenantId, source, sha));
                if (relabelled == Outcome.UPDATED) {
                    updated++;
                    continue;
                }
                if (relabelled == Outcome.UNCHANGED) {
                    unchanged++;
                    continue;
                }
            }
            List<ChunkDraft> chunks = chunker.chunk(normalized, source.format());
            Embeddings vectors = embeddings.embed(chunks.stream().map(ChunkDraft::content).toList(), InputType.PASSAGE);
            var version = new CorpusWriter.NewVersion(tenantId, source, sha, chunks, vectors.vectors(), vectors.modelId());
            Outcome outcome = transactions.execute(status -> write(version, sha));
            if (outcome == Outcome.UNCHANGED) {
                unchanged++;
                continue;
            }
            chunkCount += chunks.size();
            if (outcome == Outcome.UPDATED) {
                updated++;
            } else {
                created++;
            }
        }
        return new IngestionResult(created, updated, unchanged, chunkCount);
    }

    /**
     * Runs inside the write transaction: the pre-check above is only an optimization, so the decision is taken again under the document lock.
     */
    private Outcome write(CorpusWriter.NewVersion version, String sha) {
        CorpusWriter.LockedDocument document = writer.lockDocument(version.tenantId(), version.source().key());
        if (document.hasContent(sha, version.source().format()) && document.hasLabels(version.source().labels())) {
            return Outcome.UNCHANGED;
        }
        writer.writeVersion(version, document);
        return document.versionNo() == 0 || document.deleted() ? Outcome.CREATED : Outcome.UPDATED;
    }

    /**
     * The label-only path: no chunking and no model call, so a change of labels does not depend on the model service. Returns
     * {@code CONTENT_CHANGED} when the content changed between the pre-check and the lock; the caller then takes the full path.
     */
    private Outcome relabel(String tenantId, SourceDocument source, String sha) {
        CorpusWriter.LockedDocument document = writer.lockDocument(tenantId, source.key());
        if (!document.hasContent(sha, source.format())) {
            return Outcome.CONTENT_CHANGED;
        }
        if (document.hasLabels(source.labels())) {
            return Outcome.UNCHANGED;
        }
        writer.relabel(source, document);
        return Outcome.UPDATED;
    }

    private enum Outcome {
        CREATED,
        UPDATED,
        UNCHANGED,
        CONTENT_CHANGED
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
