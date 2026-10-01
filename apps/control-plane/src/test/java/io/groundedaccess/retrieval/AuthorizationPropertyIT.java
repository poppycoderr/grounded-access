package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.corpus.Vectors;
import io.groundedaccess.identity.Principal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Checks the compiled predicate against a reference evaluator that is written separately, in plain Java, from the decision table. The reference
 * exists only here, so the runtime keeps a single implementation. Rows are inserted through JDBC arrays, not through the production array
 * literal, so a quoting bug cannot cancel itself out.
 */
class AuthorizationPropertyIT {

    private static final List<String> LEVELS = List.of("public", "internal", "confidential", "restricted");

    private static final List<String> TENANTS = List.of("northstar", "external");

    /** Ordinary names plus values that would break naive quoting of SQL strings or array literals. */
    private static final List<String> NAMES = List.of("engineering", "support", "atlas", "a,b", "qu\"ote", "{brace}", "back\\slash", "O'Brien", "x' or '1'='1", " ");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final DriverManagerDataSource DATA_SOURCE;

    private static final JdbcClient JDBC;

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).locations("classpath:db/migration").load().migrate();
        JDBC = JdbcClient.create(DATA_SOURCE);
    }

    private static final float[] VECTOR = new float[384];

    static {
        Arrays.fill(VECTOR, 0.05f);
    }

    private final PolicyCompiler compiler = new PolicyCompiler();

    private final AuthorizedChunkQuery chunks = new AuthorizedChunkQuery(JDBC);

    record Labelled(
            String tenant,

            String classification,

            Set<String> departments,

            Set<String> projects) {
    }

    @Property(tries = 150)
    void everyQueryPathAdmitsExactlyTheDocumentsTheReferenceAllows(@ForAll("corpora") List<Labelled> corpus, @ForAll("principals") Principal principal)
            throws SQLException {
        store(corpus);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < corpus.size(); i++) {
            if (referenceAllows(principal, corpus.get(i))) {
                expected.add("doc-" + i);
            }
        }
        AuthorizationPredicate predicate = compiler.compile(principal);

        assertThat(chunks.list(predicate, null, 1000)).extracting(AuthorizedChunk::documentKey).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(chunks.sparse("volunteer", predicate, 1000)).extracting(RetrievedChunk::documentKey).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(chunks.dense(VECTOR, predicate, 1000)).extracting(RetrievedChunk::documentKey).containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * The decision table of docs/architecture/authorization.md, row by row.
     */
    private static boolean referenceAllows(Principal principal, Labelled document) {
        boolean sameTenant = principal.tenantId().equals(document.tenant());
        String claim = principal.clearance();
        int clearance = claim == null ? 0 : Math.max(0, LEVELS.indexOf(claim));
        boolean cleared = LEVELS.indexOf(document.classification()) <= clearance;
        boolean department = document.departments().isEmpty() || (principal.department() != null && document.departments().contains(principal.department()));
        boolean project = document.projects().isEmpty() || document.projects().stream().anyMatch(principal.projects()::contains);
        return sameTenant && cleared && department && project;
    }

    @Provide
    Arbitrary<List<Labelled>> corpora() {
        return Combinators.combine(Arbitraries.of(TENANTS), Arbitraries.of(LEVELS), names(), names()).as(Labelled::new).list().ofMinSize(1).ofMaxSize(12);
    }

    @Provide
    Arbitrary<Principal> principals() {
        Arbitrary<@Nullable String> clearance = Arbitraries.of("public", "internal", "confidential", "restricted", "top-secret", "INTERNAL").injectNull(0.15);
        Arbitrary<@Nullable String> department = Arbitraries.of(NAMES).injectNull(0.2);
        return Combinators.combine(Arbitraries.of(TENANTS), department, names(), clearance)
                .as((tenant, dept, projects, level) -> new Principal("subject", tenant, dept, projects, level, null));
    }

    private static Arbitrary<Set<String>> names() {
        return Arbitraries.of(NAMES).set().ofMaxSize(3);
    }

    private static void store(List<Labelled> corpus) throws SQLException {
        JDBC.sql("truncate chunk, document_version, document, tenant cascade").update();
        for (String tenant : TENANTS) {
            JDBC.sql("insert into tenant (id, name) values (:id, :id)").param("id", tenant).update();
        }
        try (Connection connection = DATA_SOURCE.getConnection();
                PreparedStatement version = connection.prepareStatement("""
                        insert into document_version (id, document_id, tenant_id, version_no, content_sha256, title, format, chunker_version,
                            embedding_model, classification, allowed_departments, required_projects, labels_sha256)
                        values (?, ?, ?, 1, 'sha', 'title', 'markdown', 'markdown/2', 'model', ?, ?, ?, 'labels')
                        """)) {
            List<UUID[]> ids = new ArrayList<>();
            for (int i = 0; i < corpus.size(); i++) {
                Labelled document = corpus.get(i);
                UUID documentId = UUID.randomUUID();
                UUID versionId = UUID.randomUUID();
                ids.add(new UUID[] {documentId, versionId});
                JDBC.sql("insert into document (id, tenant_id, external_key) values (:id, :tenant, :key)")
                        .param("id", documentId)
                        .param("tenant", document.tenant())
                        .param("key", "doc-" + i)
                        .update();
                version.setObject(1, versionId);
                version.setObject(2, documentId);
                version.setString(3, document.tenant());
                version.setString(4, document.classification());
                version.setArray(5, connection.createArrayOf("text", document.departments().toArray()));
                version.setArray(6, connection.createArrayOf("text", document.projects().toArray()));
                version.executeUpdate();
                JDBC.sql("""
                                insert into chunk (id, tenant_id, document_id, version_id, ordinal, char_start, char_end, content, embedding, token_count)
                                values (:id, :tenant, :document, :version, 0, 0, 9, 'volunteer', cast(:embedding as vector), 1)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("tenant", document.tenant())
                        .param("document", documentId)
                        .param("version", versionId)
                        .param("embedding", Vectors.toLiteral(VECTOR))
                        .update();
                JDBC.sql("update document set active_version_id = :version, last_version_no = 1 where id = :id").param("version", versionId).param("id", documentId).update();
            }
        }
    }
}
