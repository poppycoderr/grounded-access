package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.corpus.Vectors;
import io.groundedaccess.identity.Principal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Checks the compiled predicate and the scope condition against a reference evaluator that is written separately, in plain Java, from the decision table. The reference
 * exists only here, so the runtime keeps a single implementation. Rows are inserted through JDBC arrays, not through the production array
 * literal, so a quoting bug cannot cancel itself out.
 */
class AuthorizationPropertyIT {

    private static final List<String> LEVELS = List.of("public", "internal", "confidential", "restricted");

    private static final List<String> TENANTS = List.of("northstar", "external");

    /** Ordinary names plus values that would break naive quoting of SQL strings or array literals. */
    private static final List<String> NAMES = List.of("engineering", "support", "atlas", "a,b", "qu\"ote", "{brace}", "back\\slash", "O'Brien", "x' or '1'='1", " ");

    private static final List<String> REGIONS = List.of("EU", "US", "e,u", "A'PAC");

    private static final Instant T1 = Instant.parse("2025-01-01T00:00:00Z");

    private static final Instant T2 = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant T3 = Instant.parse("2027-01-01T00:00:00Z");

    /** Validity windows as {from, to}; null leaves that side open. The moments below include both boundaries of every window. */
    private static final List<@Nullable Instant[]> WINDOWS = List.of(new Instant[] {null, null}, new Instant[] {T1, T2}, new Instant[] {T2, null},
            new Instant[] {null, T2}, new Instant[] {T2, T3});

    private static final List<Instant> MOMENTS = List.of(T1.minusSeconds(1), T1, T2.minusSeconds(1), T2, T3, T3.plusSeconds(1));

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

            Set<String> projects,

            Set<String> regions,

            @Nullable Instant validFrom,

            @Nullable Instant validTo) {
    }

    @Property(tries = 150)
    void everyQueryPathAdmitsExactlyTheDocumentsTheReferenceAllows(@ForAll("corpora") List<Labelled> corpus, @ForAll("principals") Principal principal,
            @ForAll("scopes") Scope scope) throws SQLException {
        store(corpus);
        Set<String> authorized = new HashSet<>();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < corpus.size(); i++) {
            if (referenceAllows(principal, corpus.get(i))) {
                authorized.add("doc-" + i);
                if (referenceInScope(scope, corpus.get(i))) {
                    expected.add("doc-" + i);
                }
            }
        }
        AuthorizationPredicate predicate = compiler.compile(principal);

        assertThat(chunks.list(predicate, scope, null, 1000)).extracting(AuthorizedChunk::documentKey).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(chunks.sparse("volunteer", predicate, scope, 1000)).extracting(RetrievedChunk::documentKey)
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(chunks.dense(VECTOR, predicate, scope, 1000)).extracting(RetrievedChunk::documentKey).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(chunks.list(predicate, Scope.ANY, null, 1000)).extracting(AuthorizedChunk::documentKey)
                .as("without a scope, exactly the authorized documents")
                .containsExactlyInAnyOrderElementsOf(authorized);
    }

    /**
     * Scope as ADR-0005 defines it: the validity window contains the moment, and the document applies to the region or to every region.
     */
    private static boolean referenceInScope(Scope scope, Labelled document) {
        Instant asOf = scope.asOf();
        if (asOf == null) {
            return true;
        }
        boolean started = document.validFrom() == null || !document.validFrom().isAfter(asOf);
        boolean notEnded = document.validTo() == null || asOf.isBefore(document.validTo());
        boolean region = document.regions().isEmpty() || scope.region() == null || document.regions().contains(scope.region());
        return started && notEnded && region;
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
        Arbitrary<Set<String>> regions = Arbitraries.of(REGIONS).set().ofMaxSize(2);
        Arbitrary<@Nullable Instant[]> window = Arbitraries.of(WINDOWS);
        return Combinators.combine(Arbitraries.of(TENANTS), Arbitraries.of(LEVELS), names(), names(), regions, window)
                .as((tenant, level, departments, projects, where, when) -> new Labelled(tenant, level, departments, projects, where, when[0], when[1]))
                .list()
                .ofMinSize(1)
                .ofMaxSize(12);
    }

    @Provide
    Arbitrary<Scope> scopes() {
        Arbitrary<@Nullable String> region = Arbitraries.of(REGIONS).injectNull(0.3);
        return Combinators.combine(Arbitraries.of(MOMENTS), region).as(Scope::new);
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
                            embedding_model, classification, allowed_departments, required_projects, labels_sha256, applies_to_regions, valid_from,
                            valid_to, scope_sha256)
                        values (?, ?, ?, 1, 'sha', 'title', 'markdown', 'markdown/2', 'model', ?, ?, ?, 'labels', ?, ?, ?, 'scope')
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
                version.setArray(7, connection.createArrayOf("text", document.regions().toArray()));
                version.setObject(8, document.validFrom() == null ? null : document.validFrom().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                version.setObject(9, document.validTo() == null ? null : document.validTo().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
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
