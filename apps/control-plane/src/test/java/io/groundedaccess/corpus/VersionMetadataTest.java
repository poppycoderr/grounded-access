package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.authorization.Classification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.junit.jupiter.api.Test;

class VersionMetadataTest {

    @Test
    void aSetOfTwoDepartmentsIsNotTheOneDepartmentTheirOldEncodingRanTogetherInto() {
        var two = new AccessLabels(Classification.INTERNAL, Set.of("engineering", "finance"), Set.of());
        var one = new AccessLabels(Classification.INTERNAL, Set.of("engineering\u001Ffinance"), Set.of());

        assertThat(metadata(two, Set.of())).isNotEqualTo(metadata(one, Set.of()));
        assertThat(metadata(AccessLabels.UNRESTRICTED, Set.of("EU", "US"))).isNotEqualTo(metadata(AccessLabels.UNRESTRICTED, Set.of("EU\nUS")));
        assertThat(metadata(new AccessLabels(Classification.INTERNAL, Set.of("a"), Set.of("b")), Set.of()))
                .as("the same value in another field is another label set")
                .isNotEqualTo(metadata(new AccessLabels(Classification.INTERNAL, Set.of("b"), Set.of("a")), Set.of()));
    }

    @Property
    void metadataIsEqualExactlyWhenTheSetsAreEqualWhateverTheOrder(@ForAll("labelSets") List<String> first, @ForAll("labelSets") List<String> second) {
        List<String> shuffled = new ArrayList<>(first);
        Collections.reverse(shuffled);
        var labels = new AccessLabels(Classification.INTERNAL, Set.copyOf(first), Set.of());

        assertThat(metadata(labels, Set.of())).isEqualTo(metadata(new AccessLabels(Classification.INTERNAL, Set.copyOf(shuffled), Set.of()), Set.of()));
        assertThat(metadata(labels, Set.of()).equals(metadata(new AccessLabels(Classification.INTERNAL, Set.copyOf(second), Set.of()), Set.of())))
                .isEqualTo(Set.copyOf(first).equals(Set.copyOf(second)));
    }

    @Provide
    Arbitrary<List<String>> labelSets() {
        Arbitrary<String> value = Arbitraries.strings().withChars('a', 'b', ',', '\\', '"', '\n', '\u001F', ' ', '{', '}').ofMinLength(0).ofMaxLength(4);
        return value.list().ofMaxSize(4);
    }

    @Test
    void validityBoundsAreComparedAtTheMicrosecondTheDatabaseKeeps() {
        Instant stored = Instant.parse("2026-01-01T00:00:00.123456Z");

        assertThat(new DocumentScope(Set.of(), Instant.parse("2026-01-01T00:00:00.123456789Z"), null)).isEqualTo(new DocumentScope(Set.of(), stored, null));
    }

    @Test
    void aSubmittedDocumentRejectsBlankOrControlCharacterLabelValuesAndTreatsABlankSourceLinkAsNone() {
        var plain = new SourceDocument("doc", "Doc", " ", "Text.", DocumentFormat.MARKDOWN, AccessLabels.UNRESTRICTED, DocumentScope.EVERYWHERE_ALWAYS);

        assertThat(plain.sourceUri()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceDocument("doc", "Doc", null, "Text.", DocumentFormat.MARKDOWN,
                new AccessLabels(Classification.INTERNAL, Set.of("engineering\u001Ffinance"), Set.of()), DocumentScope.EVERYWHERE_ALWAYS));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceDocument("doc", "Doc", null, "Text.", DocumentFormat.MARKDOWN,
                AccessLabels.UNRESTRICTED, new DocumentScope(Set.of(" "), null, null)));
    }

    private static VersionMetadata metadata(AccessLabels labels, Set<String> regions) {
        return new VersionMetadata("Title", null, labels, new DocumentScope(regions, null, null));
    }
}
