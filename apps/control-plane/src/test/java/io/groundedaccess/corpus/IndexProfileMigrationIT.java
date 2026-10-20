package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migration V11 gives versions written before index profiles existed the profile of the code that wrote them, without touching their data.
 */
class IndexProfileMigrationIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void derivesEachExistingVersionsProfileFromWhatItRecorded() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("10").load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("insert into tenant (id, name) values ('northstar', 'northstar')").update();
        Map<String, List<String>> versions = Map.of(
                "markdown-context", List.of("BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55", "markdown/3"),
                "text-context", List.of("BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55", "text/3"),
                "text-plain", List.of("BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55", "text/2"),
                "unrecorded", List.of("other-model@", "markdown/3"));
        versions.forEach((key, recorded) -> {
            UUID document = UUID.randomUUID();
            jdbc.sql("insert into document (id, tenant_id, external_key) values (:id, 'northstar', :key)").param("id", document).param("key", key).update();
            jdbc.sql("""
                    insert into document_version (id, document_id, tenant_id, version_no, content_sha256, title, format, chunker_version, embedding_model,
                        classification, allowed_departments, required_projects, applies_to_regions)
                    values (:id, :document, 'northstar', 1, 'sha', 'title', :format, :chunker, :model, 'public', '{}', '{}', '{}')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("document", document)
                    .param("format", recorded.get(1).split("/")[0])
                    .param("chunker", recorded.get(1))
                    .param("model", recorded.get(0))
                    .update();
        });

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        var profiles = jdbc.sql("""
                        select d.external_key, p.embedding_model, p.embedding_revision, p.dimensions, p.query_prefix, p.passage_prefix, p.embedding_context,
                            p.chunker_release, p.chunk_max_words, p.chunk_overlap_words, p.id
                        from document d join document_version v on v.document_id = d.id join index_profile p on p.id = v.index_profile_id
                        order by d.external_key
                        """)
                .query((rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                        rs.getBoolean(7), rs.getString(8), rs.getInt(9), rs.getInt(10)))
                .list();
        String prefix = "Represent this sentence for searching relevant passages: ";
        String revision = "aa8f8b060edb00e03bfdd08813a2949946c8ba55";
        assertThat(profiles).containsExactly(
                List.of("markdown-context", "BAAI/bge-small-en-v1.5", revision, 384, prefix, "", true, "3", 180, 30),
                List.of("text-context", "BAAI/bge-small-en-v1.5", revision, 384, prefix, "", true, "3", 180, 30),
                List.of("text-plain", "BAAI/bge-small-en-v1.5", revision, 384, prefix, "", false, "2", 180, 30),
                List.of("unrecorded", "other-model", "unknown", 384, "", "", true, "3", 180, 30));
        assertThat(jdbc.sql("select count(*) from index_profile").query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select distinct chunk_max_tokens + chunk_max_chars + heading_max_chars from index_profile").query(Integer.class).list())
                .as("profiles of releases before 4 had no length limits")
                .containsExactly(0);
        assertThat(jdbc.sql("select count(*) from document_version where embedding_model like 'BAAI/%'").query(Integer.class).single()).isEqualTo(3);
    }
}
