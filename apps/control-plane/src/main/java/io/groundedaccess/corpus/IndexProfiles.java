package io.groundedaccess.corpus;

import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;
import io.groundedaccess.modelclient.InvalidModelResponseException;
import io.groundedaccess.telemetry.Failures;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Knows the index profile this process embeds with and checks every embedding against it (ADR-0007).
 *
 * <p>
 * The profile is established once: from two probe embeddings (one query, one passage) that report model, revision, dimensions and prefixes,
 * plus this process's chunking settings. It is established only if every active version has that same profile, or there is none yet, and the
 * vector column has its dimensions. From then on every embedding must report the same model, revision, dimensions and prefix, so a model
 * service replaced while running is noticed on its first answer.
 *
 * <p>
 * At startup a profile that differs from the stored one stops the application. A model service that cannot be reached does not: the profile
 * is then established on the first embedding that is needed, and until then dense search and ingestion fail as they do when the service is down.
 */
@Component
public class IndexProfiles implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexProfiles.class);

    /** A fixed, non-sensitive text whose embeddings report the profile of the model service. */
    public static final String PROBE = "index profile check";

    private static final String COLUMNS = """
            embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix, embedding_context, chunker_release, chunk_max_words,
            chunk_overlap_words, chunk_max_tokens, chunk_max_chars, heading_max_chars""";

    private final JdbcClient jdbc;

    private final EmbeddingClient embeddings;

    private final DocumentChunker chunker;

    private final boolean embeddingContext;

    private volatile @Nullable Established established;

    /** Establishes the profile off the request path; at most one attempt runs at a time. */
    private final ExecutorService background = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("index-profile").factory());

    private final AtomicBoolean establishing = new AtomicBoolean();

    public IndexProfiles(JdbcClient jdbc, EmbeddingClient embeddings, @Value("${ga.corpus.chunk-max-words:180}") int chunkMaxWords,
            @Value("${ga.corpus.chunk-overlap-words:30}") int chunkOverlapWords, @Value("${ga.corpus.embedding-context:true}") boolean embeddingContext) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.chunker = new DocumentChunker(chunkMaxWords, chunkOverlapWords);
        this.embeddingContext = embeddingContext;
    }

    /**
     * The profile of the index and its id, which every new version and every dense search names.
     */
    public record Established(
            UUID id,

            IndexProfile profile) {
    }

    @Override
    public void run(ApplicationArguments args) {
        checkAtStartup();
    }

    void checkAtStartup() {
        for (IndexProfile stored : stored()) {
            if (!stored.embeddingModel().equals(embeddings.modelName()) || stored.embeddingContext() != embeddingContext
                    || !stored.chunkerRelease().equals(DocumentChunker.release()) || stored.chunkMaxWords() != chunker.maxWords()
                    || stored.chunkOverlapWords() != chunker.overlapWords() || stored.chunkMaxTokens() != DocumentChunker.MAX_TOKENS
                    || stored.chunkMaxChars() != DocumentChunker.MAX_CHARS || stored.headingMaxChars() != DocumentChunker.HEADING_MAX_CHARS) {
                throw refusal("Active documents were indexed with " + stored.describe() + ", which this configuration cannot produce");
            }
        }
        try {
            log.info("Index profile: {}", establish().profile().describe());
        } catch (IndexProfileMismatchException | InvalidModelResponseException | HttpClientErrorException e) {
            throw refusal(e.getMessage());
        } catch (RestClientException e) {
            log.warn("The model service could not be reached to check the index profile; dense search and ingestion wait for it: {}",
                    Failures.describe(e));
        }
    }

    private static IllegalStateException refusal(@Nullable String reason) {
        return new IllegalStateException(reason + ". Configure the previous model and settings again, or load the corpus into an empty database.");
    }

    /**
     * Checks a query embedding and returns the profile id that dense search must restrict stored vectors to.
     */
    public UUID checkQuery(Embeddings query) {
        return check(query, InputType.QUERY);
    }

    /**
     * Checks the embeddings of a new version's chunks and returns the profile id the version is written with.
     */
    UUID checkPassages(Embeddings passages) {
        return check(passages, InputType.PASSAGE);
    }

    DocumentChunker chunker() {
        return chunker;
    }

    boolean embeddingContext() {
        return embeddingContext;
    }

    private UUID check(Embeddings vectors, InputType inputType) {
        Established current = establish();
        IndexProfile profile = current.profile();
        String prefix = inputType == InputType.QUERY ? profile.queryPrefix() : profile.passagePrefix();
        if (!vectors.model().equals(profile.embeddingModel()) || !vectors.revision().equals(profile.embeddingRevision())
                || vectors.dimensions() != profile.dimensions() || !vectors.prefix().equals(prefix)) {
            throw new IndexProfileMismatchException("The model service now embeds with %s, %d dimensions, prefix \"%s\"; the index is %s".formatted(
                    vectors.modelId(), vectors.dimensions(), vectors.prefix(), profile.describe()));
        }
        return current.id();
    }

    /**
     * The established profile, or empty while it is not. Never waits: when the profile is missing, one attempt to establish it starts in the
     * background, so a search does not spend its deadline on the two probe embeddings, and concurrent searches do not each start one. A
     * search that finds no profile answers without its dense channel (review 2026-10-21, N1).
     */
    public Optional<Established> ready() {
        Established current = established;
        if (current != null) {
            return Optional.of(current);
        }
        if (establishing.compareAndSet(false, true)) {
            background.execute(() -> {
                try {
                    establish();
                } catch (RuntimeException e) {
                    log.warn("The index profile could not be established yet: {}", Failures.describe(e));
                } finally {
                    establishing.set(false);
                }
            });
        }
        return Optional.empty();
    }

    /**
     * As {@link #ready()}; for describing the deployment.
     */
    public Optional<Established> current() {
        return ready();
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    /**
     * Establishes the profile on first use; a failed attempt is not remembered, so a model service that is fixed or comes back is picked up
     * without a restart.
     */
    Established establish() {
        Established current = established;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = established;
            if (current == null) {
                current = establishNow();
                established = current;
            }
            return current;
        }
    }

    private Established establishNow() {
        Embeddings query = embeddings.embed(List.of(PROBE), InputType.QUERY);
        Embeddings passage = embeddings.embed(List.of(PROBE), InputType.PASSAGE);
        if (!query.modelId().equals(passage.modelId()) || query.dimensions() != passage.dimensions()) {
            throw new IndexProfileMismatchException(
                    "The model service changed from %s to %s between two calls".formatted(query.modelId(), passage.modelId()));
        }
        IndexProfile observed = new IndexProfile(query.model(), query.revision(), query.dimensions(), query.prefix(), passage.prefix(), embeddingContext,
                DocumentChunker.release(), chunker.maxWords(), chunker.overlapWords(), DocumentChunker.MAX_TOKENS, DocumentChunker.MAX_CHARS,
                DocumentChunker.HEADING_MAX_CHARS);
        int column = vectorColumnDimensions();
        if (observed.dimensions() != column) {
            throw new IndexProfileMismatchException(
                    "The model service embeds with %d dimensions; the vector column has %d".formatted(observed.dimensions(), column));
        }
        for (IndexProfile stored : stored()) {
            if (!stored.equals(observed)) {
                throw new IndexProfileMismatchException("Active documents were indexed with %s; the model service and this configuration produce %s"
                        .formatted(stored.describe(), observed.describe()));
            }
        }
        return new Established(save(observed), observed);
    }

    /**
     * Claims the database for {@code profile}, or confirms it is already claimed for it, in the caller's write transaction. The first write
     * anchors the database; every later write with another profile is refused, also from a process that established its profile while the
     * database was empty. The insert is atomic, so two first writers cannot both win (review 2026-10-21, D6).
     */
    void claim(UUID profile) {
        jdbc.sql("insert into index_anchor (index_profile_id) values (:profile) on conflict (singleton) do nothing").param("profile", profile).update();
        UUID anchored = jdbc.sql("select index_profile_id from index_anchor for key share").query(UUID.class).single();
        if (!anchored.equals(profile)) {
            throw new IndexProfileMismatchException(
                    "This database is indexed with %s; this process writes %s".formatted(describe(anchored), describe(profile)));
        }
    }

    private String describe(UUID id) {
        return jdbc.sql("select %s from index_profile where id = :id".formatted(COLUMNS)).param("id", id).query((rs, i) -> profile(rs).describe()).single();
    }

    /**
     * The profiles of the active versions and the profile the database is anchored to, which survives the database being emptied.
     */
    private List<IndexProfile> stored() {
        return jdbc.sql("""
                        select %s
                        from index_profile
                        where id in (select v.index_profile_id from document d join document_version v on v.id = d.active_version_id)
                            or id in (select index_profile_id from index_anchor)
                        """.formatted(COLUMNS))
                .query((rs, i) -> profile(rs))
                .list();
    }

    private UUID save(IndexProfile profile) {
        jdbc.sql("""
                        insert into index_profile (id, %s)
                        values (:id, :model, :revision, :dimensions, :queryPrefix, :passagePrefix, :context, :release, :maxWords, :overlap, :maxTokens,
                            :maxChars, :headingChars)
                        on conflict do nothing
                        """.formatted(COLUMNS))
                .param("id", UUID.randomUUID())
                .param("model", profile.embeddingModel())
                .param("revision", profile.embeddingRevision())
                .param("dimensions", profile.dimensions())
                .param("queryPrefix", profile.queryPrefix())
                .param("passagePrefix", profile.passagePrefix())
                .param("context", profile.embeddingContext())
                .param("release", profile.chunkerRelease())
                .param("maxWords", profile.chunkMaxWords())
                .param("overlap", profile.chunkOverlapWords())
                .param("maxTokens", profile.chunkMaxTokens())
                .param("maxChars", profile.chunkMaxChars())
                .param("headingChars", profile.headingMaxChars())
                .update();
        return jdbc.sql("""
                        select id from index_profile
                        where embedding_model = :model and embedding_revision = :revision and dimensions = :dimensions and query_prefix = :queryPrefix
                            and passage_prefix = :passagePrefix and embedding_context = :context and chunker_release = :release
                            and chunk_max_words = :maxWords and chunk_overlap_words = :overlap and chunk_max_tokens = :maxTokens
                            and chunk_max_chars = :maxChars and heading_max_chars = :headingChars
                        """)
                .param("model", profile.embeddingModel())
                .param("revision", profile.embeddingRevision())
                .param("dimensions", profile.dimensions())
                .param("queryPrefix", profile.queryPrefix())
                .param("passagePrefix", profile.passagePrefix())
                .param("context", profile.embeddingContext())
                .param("release", profile.chunkerRelease())
                .param("maxWords", profile.chunkMaxWords())
                .param("overlap", profile.chunkOverlapWords())
                .param("maxTokens", profile.chunkMaxTokens())
                .param("maxChars", profile.chunkMaxChars())
                .param("headingChars", profile.headingMaxChars())
                .query(UUID.class)
                .single();
    }

    private int vectorColumnDimensions() {
        return Objects.requireNonNull(jdbc.sql("select atttypmod from pg_attribute where attrelid = 'chunk'::regclass and attname = 'embedding'")
                .query(Integer.class)
                .single());
    }

    private static IndexProfile profile(ResultSet rs) throws SQLException {
        return new IndexProfile(rs.getString("embedding_model"), rs.getString("embedding_revision"), rs.getInt("dimensions"), rs.getString("query_prefix"),
                rs.getString("passage_prefix"), rs.getBoolean("embedding_context"), rs.getString("chunker_release"), rs.getInt("chunk_max_words"),
                rs.getInt("chunk_overlap_words"), rs.getInt("chunk_max_tokens"), rs.getInt("chunk_max_chars"), rs.getInt("heading_max_chars"));
    }
}
