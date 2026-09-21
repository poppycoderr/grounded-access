package io.groundedaccess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationIT {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void migratesCorpusSchemaOnAFreshDatabase() {
        var tables = jdbc.sql("select table_name from information_schema.tables where table_schema = 'public'").query(String.class).set();
        assertThat(tables).contains("tenant", "document", "document_version", "chunk", "ingestion_job", "ingestion_job_document");
    }

    @Test
    void enablesPgvectorWithTheConfiguredDimension() {
        var type = jdbc.sql("select format_type(atttypid, atttypmod) from pg_attribute where attrelid = 'chunk'::regclass and attname = 'embedding'")
                .query(String.class)
                .single();
        assertThat(type).isEqualTo("vector(384)");
    }

    @Test
    void indexesChunkTextForEnglishFullTextSearch() {
        var matches = jdbc.sql("select to_tsvector('english', 'Employees receive paid volunteer days') @@ to_tsquery('english', 'volunteering')")
                .query(Boolean.class)
                .single();
        assertThat(matches).isTrue();
    }
}
