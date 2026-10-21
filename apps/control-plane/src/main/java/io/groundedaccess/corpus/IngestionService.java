package io.groundedaccess.corpus;

import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingests documents into one tenant. A document whose normalized content is unchanged is skipped; otherwise it gets a new version. Embedding
 * happens before the transaction so no database connection is held during model calls, and the write decision is repeated under a document lock so
 * concurrent ingestions of the same key stay consistent. Embeddings of another index profile than the stored one are refused before anything
 * is written (ADR-0007).
 */
@Service
public class IngestionService {

    private final CorpusWriter writer;

    private final EmbeddingClient embeddings;

    private final TransactionTemplate transactions;

    private final IndexProfiles profiles;

    private final DocumentChunker chunker;

    private final boolean embeddingContext;

    public IngestionService(CorpusWriter writer, EmbeddingClient embeddings, TransactionTemplate transactions, IndexProfiles profiles) {
        this.writer = writer;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.profiles = profiles;
        this.chunker = profiles.chunker();
        this.embeddingContext = profiles.embeddingContext();
    }

    public IngestionResult ingest(String tenantId, List<SourceDocument> documents) {
        IngestionResult total = new IngestionResult(0, 0, 0, 0);
        for (SourceDocument source : documents) {
            IngestionResult one = ingest(tenantId, source, WriteFence.NONE);
            total = new IngestionResult(total.created() + one.created(), total.updated() + one.updated(), total.unchanged() + one.unchanged(),
                    total.chunks() + one.chunks());
        }
        return total;
    }

    /**
     * Ingests one document. Chunking and embedding run outside any transaction; every outcome, including "unchanged", is then committed in
     * one short transaction that starts with {@code fence.verify()} and ends with {@code fence.record(result)}, so the document and whatever
     * the fence records change together or not at all.
     */
    public IngestionResult ingest(String tenantId, SourceDocument source, WriteFence fence) {
        writer.ensureTenant(tenantId);
        String normalized = DocumentChunker.normalize(source.content());
        String sha = sha256(normalized);
        Optional<CorpusWriter.ActiveVersion> active = writer.findActive(tenantId, source.key()).filter(a -> a.hasContent(sha, source.format()));
        if (active.isPresent()) {
            if (active.get().hasMetadata(source)) {
                return commit(fence, () -> Outcome.UNCHANGED, 0).orElseThrow();
            }
            Optional<IngestionResult> relabelled = commit(fence, () -> relabel(tenantId, source, sha), 0);
            if (relabelled.isPresent()) {
                return relabelled.get();
            }
        }
        IndexProfiles.Established index = profiles.establish();
        List<ChunkDraft> planned = chunker.chunk(normalized, source.format());
        // Tokenized before embedding, so that no chunk is embedded from a cut input (ADR-0009)
        List<ChunkDraft> chunks = planned.isEmpty() ? planned : DocumentChunker.fit(planned, normalized, embeddingContext, embeddings::fittingChars);
        List<float[]> vectors = List.of();
        if (!chunks.isEmpty()) {
            Embeddings embedded = embeddings.embed(chunks.stream()
                    .map(chunk -> embeddingContext ? DocumentChunker.withContext(chunk.sectionPath(), chunk.content()) : chunk.content())
                    .toList(), InputType.PASSAGE);
            profiles.checkPassages(embedded);
            vectors = embedded.vectors();
        }
        var version = new CorpusWriter.NewVersion(tenantId, source, sha, chunks, vectors, index.profile().embeddingModel() + "@"
                + index.profile().embeddingRevision(), index.id(), DocumentChunker.version(source.format()));
        return commit(fence, () -> write(version, sha), chunks.size()).orElseThrow();
    }

    /**
     * Runs one decision in a transaction between the fence's two calls. Returns nothing when the decision found that the content changed after
     * the pre-check, which writes nothing; the caller then takes the full path.
     */
    private Optional<IngestionResult> commit(WriteFence fence, Supplier<Outcome> decision, int chunks) {
        return Objects.requireNonNull(transactions.execute(status -> {
            fence.verify();
            Outcome outcome = decision.get();
            if (outcome == Outcome.CONTENT_CHANGED) {
                return Optional.<IngestionResult>empty();
            }
            IngestionResult result = switch (outcome) {
                case CREATED -> new IngestionResult(1, 0, 0, chunks);
                case UPDATED -> new IngestionResult(0, 1, 0, chunks);
                case UNCHANGED, CONTENT_CHANGED -> new IngestionResult(0, 0, 1, 0);
            };
            fence.record(result);
            return Optional.of(result);
        }));
    }

    /**
     * Runs inside the write transaction: the pre-check above is only an optimization, so the decision is taken again under the document lock.
     */
    private Outcome write(CorpusWriter.NewVersion version, String sha) {
        CorpusWriter.LockedDocument document = writer.lockDocument(version.tenantId(), version.source().key());
        if (document.hasContent(sha, version.source().format()) && document.hasMetadata(version.source())) {
            return Outcome.UNCHANGED;
        }
        // Lock order: job, document, then the database's index anchor
        profiles.claim(version.indexProfileId());
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
        if (document.hasMetadata(source)) {
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
