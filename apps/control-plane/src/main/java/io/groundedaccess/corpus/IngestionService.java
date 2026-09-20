package io.groundedaccess.corpus;

import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

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

    private final MarkdownChunker chunker;

    public IngestionService(CorpusWriter writer, EmbeddingClient embeddings, TransactionTemplate transactions,
            @Value("${ga.corpus.chunk-max-words:180}") int chunkMaxWords) {
        this.writer = writer;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.chunker = new MarkdownChunker(chunkMaxWords);
    }

    public IngestionResult ingest(String tenantId, List<SourceDocument> documents) {
        writer.ensureTenant(tenantId);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int chunkCount = 0;
        for (SourceDocument source : documents) {
            String normalized = MarkdownChunker.normalize(source.content());
            String sha = sha256(normalized);
            if (writer.findActive(tenantId, source.key()).filter(active -> active.contentSha256().equals(sha)).isPresent()) {
                unchanged++;
                continue;
            }
            List<ChunkDraft> chunks = chunker.chunk(normalized);
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
        if (document.alreadyHas(sha)) {
            return Outcome.UNCHANGED;
        }
        writer.writeVersion(version, document);
        return document.versionNo() == 0 ? Outcome.CREATED : Outcome.UPDATED;
    }

    private enum Outcome {
        CREATED,
        UPDATED,
        UNCHANGED
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
