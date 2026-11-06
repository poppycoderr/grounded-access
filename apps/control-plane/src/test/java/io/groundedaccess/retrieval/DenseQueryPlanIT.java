package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.identity.Principal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The dense query costs the same under a generic plan as under a custom one. The JDBC driver prepares a statement on the server after five
 * executions, and PostgreSQL then may reuse a generic plan, in which a query vector cast from text in the ORDER BY was cast again for every
 * row it was compared with: the scale run of v0.2 measured dense search at about 800 ms instead of 60 ms on 33,000 chunks from the tenth
 * search of each pooled connection on.
 */
class DenseQueryPlanIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final int CHUNKS = 6000;

    private static final UUID PROFILE = UUID.randomUUID();

    private static final Principal READER = new Principal("reader", "northstar", null, Set.of(), "internal", null);

    @BeforeAll
    static void corpus() {
        POSTGRES.start();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                insert into index_profile (id, embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix, embedding_context,
                    chunker_release, chunk_max_words, chunk_overlap_words, chunk_max_tokens, chunk_max_chars, heading_max_chars, chunk_tokenizer)
                values (:id, 'model', 'test', 384, '', '', false, '4', 180, 30, 384, 2000, 200, 'words')
                """).param("id", PROFILE).update();
        jdbc.sql("insert into tenant (id, name) values ('northstar', 'northstar')").update();
        jdbc.sql("""
                insert into document (id, tenant_id, external_key, last_version_no)
                select gen_random_uuid(), 'northstar', 'doc-' || n, 1 from generate_series(1, :documents) n
                """).param("documents", CHUNKS / 10).update();
        jdbc.sql("""
                insert into document_version (id, document_id, tenant_id, version_no, content_sha256, title, format, chunker_version, embedding_model,
                    index_profile_id, classification, allowed_departments, required_projects, applies_to_regions)
                select gen_random_uuid(), d.id, 'northstar', 1, 'sha', 'title', 'markdown', 'markdown/4', 'model@test', :profile, 'internal', '{}', '{}', '{}'
                from document d
                """).param("profile", PROFILE).update();
        jdbc.sql("update document d set active_version_id = v.id from document_version v where v.document_id = d.id").update();
        jdbc.sql("""
                insert into chunk (id, tenant_id, document_id, version_id, ordinal, char_start, char_end, content, embedding, token_count)
                select gen_random_uuid(), 'northstar', v.document_id, v.id, o, 0, 9, 'volunteer', r.embedding, 1
                from document_version v cross join generate_series(0, 9) o
                    cross join lateral (select array_agg(random() - 0.5)::vector as embedding from generate_series(1, 384) where o >= 0 and v.id is not null) r
                """).update();
        jdbc.sql("analyze").update();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void aGenericPlanComparesEveryRowWithoutCastingTheQueryVectorAgain() throws SQLException {
        float[] query = new float[384];
        Random random = new Random(7);
        for (int i = 0; i < query.length; i++) {
            query[i] = random.nextFloat() - 0.5f;
        }

        Measured custom = measure("force_custom_plan", query);
        Measured generic = measure("force_generic_plan", query);

        assertThat(generic.keys()).as("both plans rank alike").isEqualTo(custom.keys());
        assertThat(generic.medianMillis()).as("generic %.1f ms, custom %.1f ms", generic.medianMillis(), custom.medianMillis())
                .isLessThan(custom.medianMillis() * 3 + 25);
    }

    private record Measured(
            List<String> keys,

            double medianMillis) {
    }

    /**
     * Runs the dense query eleven times on one connection that prepares every statement on the server and plans it as {@code planCacheMode}
     * says, and returns its ranking and the median time.
     */
    private static Measured measure(String planCacheMode, float[] query) throws SQLException {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "prepareThreshold=1";
        try (var single = new SingleConnectionDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
            try (Connection connection = single.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("set plan_cache_mode = " + planCacheMode);
            }
            AuthorizedChunkQuery chunks = new AuthorizedChunkQuery(JdbcClient.create(single));
            var predicate = new PolicyCompiler().compile(READER);
            List<Long> nanos = new ArrayList<>();
            List<String> keys = List.of();
            for (int i = 0; i < 11; i++) {
                long started = System.nanoTime();
                keys = chunks.dense(query, PROFILE, predicate, Scope.ANY, 50).stream().map(c -> c.chunkId().toString()).toList();
                nanos.add(System.nanoTime() - started);
            }
            List<Long> sorted = nanos.stream().sorted().toList();
            return new Measured(keys, sorted.get(sorted.size() / 2) / 1_000_000.0);
        }
    }
}
