package io.groundedaccess;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    private static boolean readsChunks(Path path) {
        try {
            return CHUNK_READ.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
