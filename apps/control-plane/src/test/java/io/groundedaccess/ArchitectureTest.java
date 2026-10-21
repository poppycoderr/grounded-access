package io.groundedaccess;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.groundedaccess.retrieval.AuthorizedChunk;
import io.groundedaccess.retrieval.AuthorizedChunkQuery;
import io.groundedaccess.retrieval.RetrievedChunk;

import org.junit.jupiter.api.Test;

/**
 * Enforces the module rules from docs/architecture/overview.md.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.groundedaccess");

    private static final Pattern CHUNK_READ = Pattern.compile("(?i)\\b(from|join)\\s+chunk\\b");

    @Test
    void onlyAuthorizedChunkQueryReadsTheChunkTable() throws IOException {
        List<Path> readers;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            readers = sources.filter(p -> p.toString().endsWith(".java")).filter(ArchitectureTest::readsChunks).toList();
        }
        assertThat(readers).extracting(p -> p.getFileName().toString()).containsExactly("AuthorizedChunkQuery.java");
    }

    @Test
    void authorizationDoesNotDependOnWebRetrievalOrPersistence() {
        noClasses().that().resideInAPackage("..authorization..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..retrieval..", "org.springframework.web..", "org.springframework.jdbc..")
                .check(CLASSES);
    }

    @Test
    void controllersDoNotTouchTheDatabase() {
        noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..").check(CLASSES);
    }

    @Test
    void onlyModelClientKnowsTheModelServiceWireFormat() {
        noClasses().that().resideOutsideOfPackage("..modelclient..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("ModelService")
                .check(CLASSES);
    }

    /**
     * Which packages each package may use. Read bottom-up: nothing below a package knows about it, so the graph has no cycles, and a new
     * dependency is a decision that changes this table.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("identity", Set.of()),
            Map.entry("telemetry", Set.of()),
            Map.entry("modelclient", Set.of()),
            Map.entry("authorization", Set.of("identity")),
            Map.entry("audit", Set.of("authorization", "identity")),
            Map.entry("corpus", Set.of("audit", "authorization", "identity", "modelclient", "telemetry")),
            Map.entry("retrieval", Set.of("audit", "authorization", "corpus", "identity", "modelclient", "telemetry")),
            Map.entry("ingestion", Set.of("audit", "authorization", "corpus", "identity", "modelclient", "telemetry")),
            Map.entry("answering", Set.of("audit", "identity", "modelclient", "retrieval", "telemetry")),
            Map.entry("api", Set.of("answering", "audit", "authorization", "corpus", "identity", "ingestion", "modelclient", "retrieval", "telemetry")));

    @Test
    void everyPackageUsesOnlyThePackagesItIsAllowedTo() {
        Set<String> packages = new TreeSet<>(ALLOWED.keySet());
        assertThat(CLASSES.stream()
                .map(JavaClass::getPackageName)
                .filter(name -> name.startsWith("io.groundedaccess."))
                .map(name -> name.substring("io.groundedaccess.".length()).split("\\.")[0])
                .distinct()).as("every package has a row in the table").allMatch(packages::contains);
        ALLOWED.forEach((module, allowed) -> {
            String[] forbidden = packages.stream().filter(other -> !other.equals(module) && !allowed.contains(other)).map(other -> "io.groundedaccess." + other + "..")
                    .toArray(String[]::new);
            if (forbidden.length > 0) {
                noClasses().that().resideInAPackage("io.groundedaccess." + module + "..").should().dependOnClassesThat().resideInAnyPackage(forbidden).check(CLASSES);
            }
        });
    }

    @Test
    void packagesHaveNoCycles() {
        slices().matching("io.groundedaccess.(*)..").should().beFreeOfCycles().check(CLASSES);
    }

    @Test
    void answeringNeitherQueriesNorWritesTheCorpus() {
        noClasses().that().resideInAPackage("..answering..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "io.groundedaccess.corpus..", "io.groundedaccess.ingestion..")
                .check(CLASSES);
    }

    /**
     * Rows that left the database under the authorization predicate are created in two places only: by the query that applied the predicate,
     * and as re-ranked copies of such a row. Code that builds one any other way would hand downstream code a row nobody authorized.
     */
    @Test
    void onlyTheAuthorizedQueryCreatesAuthorizedRows() {
        DescribedPredicate<JavaClass> authorizedRows = DescribedPredicate.describe("an authorized row type",
                type -> type.isEquivalentTo(RetrievedChunk.class) || type.isEquivalentTo(AuthorizedChunk.class));
        noClasses().that().doNotHaveFullyQualifiedName(AuthorizedChunkQuery.class.getName())
                .and().doNotHaveFullyQualifiedName(RetrievedChunk.class.getName())
                .should().callConstructorWhere(target(owner(authorizedRows)))
                .check(CLASSES);
    }

    private static boolean readsChunks(Path path) {
        try {
            return CHUNK_READ.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
