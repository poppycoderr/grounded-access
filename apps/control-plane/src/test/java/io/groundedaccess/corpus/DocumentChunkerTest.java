package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker(12, 0);

    @Test
    void chunksNeverCrossHeadingsAndCarryTheHeadingPath() {
        String text = DocumentChunker.normalize("""
                # Volunteer Policy

                Intro paragraph.

                ## European Union

                EU employees receive two paid volunteer days.

                ## United States

                US employees receive one paid volunteer day.
                """);

        List<ChunkDraft> chunks = chunker.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).extracting(ChunkDraft::sectionPath)
                .containsExactly("Volunteer Policy", "Volunteer Policy > European Union", "Volunteer Policy > United States");
        assertThat(chunks.get(1).content()).isEqualTo("EU employees receive two paid volunteer days.");
    }

    @Test
    void offsetsPointIntoTheNormalizedText() {
        String text = DocumentChunker.normalize("# A\r\n\r\nfirst paragraph here\r\n\r\nsecond one\r\n");

        for (ChunkDraft chunk : chunker.chunk(text, DocumentFormat.MARKDOWN)) {
            assertThat(text.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(chunk.content());
        }
    }

    @Test
    void packsWholeParagraphsUpToTheWordLimit() {
        String text = DocumentChunker.normalize("""
                one two three four

                five six seven eight

                nine ten eleven twelve thirteen

                a b c d e f g h i j k l m n o p
                """);

        List<ChunkDraft> chunks = chunker.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).extracting(ChunkDraft::tokenCount).containsExactly(8, 5, 16);
        assertThat(chunks.getFirst().content()).isEqualTo("one two three four\n\nfive six seven eight");
    }

    @Test
    void splitsAParagraphLongerThanTheLimitBetweenSentences() {
        String text = DocumentChunker.normalize("""
                # Leave

                Parents receive sixteen weeks of paid leave. The leave starts on the birth date. Adoptive parents receive the same leave. \
                Requests go to the people team.
                """);

        List<ChunkDraft> chunks = new DocumentChunker(15, 0).chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).extracting(ChunkDraft::content)
                .containsExactly("Parents receive sixteen weeks of paid leave. The leave starts on the birth date.",
                        "Adoptive parents receive the same leave. Requests go to the people team.");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.sectionPath()).isEqualTo("Leave"));
    }

    @Test
    void treatsListItemsAsSentenceBoundaries() {
        String text = DocumentChunker.normalize("""
                Before a failover:
                - page the database owner and the incident commander
                - freeze deploys on the billing service
                - confirm the replica lag is below one second
                """);

        List<ChunkDraft> chunks = chunker.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(12));
        assertThat(chunks.getLast().content()).isEqualTo("- confirm the replica lag is below one second");
    }

    @Test
    void laterChunksOfASectionStartWithTheTrailingSentencesOfThePreviousOneWithinTheLimit() {
        DocumentChunker overlapping = new DocumentChunker(14, 6);
        String text = DocumentChunker.normalize("""
                # Meals

                Meals are reimbursed daily. The allowance is sixty euros. Receipts are optional below ten euros. Alcohol is never reimbursed.
                """);

        List<ChunkDraft> chunks = overlapping.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).extracting(ChunkDraft::content)
                .containsExactly("Meals are reimbursed daily. The allowance is sixty euros.",
                        "The allowance is sixty euros. Receipts are optional below ten euros.",
                        "Receipts are optional below ten euros. Alcohol is never reimbursed.");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(14));
    }

    @Test
    void neverOverlapsAcrossAHeading() {
        DocumentChunker overlapping = new DocumentChunker(12, 6);
        String text = DocumentChunker.normalize("""
                # A

                The first section ends here.

                # B

                The second section starts here.
                """);

        List<ChunkDraft> chunks = overlapping.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).extracting(ChunkDraft::content).containsExactly("The first section ends here.", "The second section starts here.");
    }

    @Test
    void keepsCodeFencesWholeAndIgnoresHeadingSyntaxInsideThem() {
        String text = DocumentChunker.normalize("""
                # Runbook

                ```bash
                # not a heading
                pg_ctl promote. Then check the replica. Then update the DNS record and wait for it.
                ```
                """);

        List<ChunkDraft> chunks = chunker.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().sectionPath()).isEqualTo("Runbook");
        assertThat(chunks.getFirst().content()).startsWith("```bash\n# not a heading").endsWith("```");
    }

    @Test
    void plainTextHasNoHeadingsOrFences() {
        String text = DocumentChunker.normalize("""
                # Not a heading in plain text

                ``` is just punctuation here
                """);

        List<ChunkDraft> chunks = new DocumentChunker(5, 0).chunk(text, DocumentFormat.TEXT);

        assertThat(chunks).extracting(ChunkDraft::sectionPath).containsOnly("");
        assertThat(chunks).extracting(ChunkDraft::content).containsExactly("# Not a heading in plain text", "``` is just punctuation here");
    }

    @Test
    void generatedDocumentsKeepEveryInvariant() {
        DocumentChunker overlapping = new DocumentChunker(40, 10);
        Random random = new Random(20261001);
        for (int document = 0; document < 200; document++) {
            String text = DocumentChunker.normalize(randomMarkdown(random));

            List<ChunkDraft> chunks = overlapping.chunk(text, DocumentFormat.MARKDOWN);

            assertThat(chunks).extracting(ChunkDraft::ordinal).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
            for (ChunkDraft chunk : chunks) {
                assertThat(text.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(chunk.content());
                assertThat(chunk.content()).isEqualTo(chunk.content().strip()).doesNotStartWith("#");
                boolean singleSentence = !chunk.content().strip().contains(". ");
                assertThat(chunk.tokenCount() <= 40 || singleSentence).as("chunk over the limit: %s", chunk.content()).isTrue();
            }
            String covered = chunks.stream().map(ChunkDraft::content).collect(Collectors.joining(" "));
            for (String word : text.replaceAll("(?m)^#+ .*$", "").split("\\s+")) {
                assertThat(covered).contains(word);
            }
        }
    }

    @Test
    void rejectsAnOverlapThatLeavesNoRoomForNewText() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentChunker(10, 10));
    }

    @Test
    void recordsTheFormatInTheChunkerVersion() {
        assertThat(DocumentChunker.version(DocumentFormat.MARKDOWN)).isEqualTo("markdown/2");
        assertThat(DocumentChunker.version(DocumentFormat.TEXT)).isEqualTo("text/2");
    }

    @Test
    void normalizationIsStableForHashing() {
        assertThat(DocumentChunker.normalize("﻿  text\r\n\r\n")).isEqualTo("text\n");
    }

    private static String randomMarkdown(Random random) {
        StringBuilder text = new StringBuilder();
        for (int section = 0; section < 1 + random.nextInt(4); section++) {
            text.append("#".repeat(1 + random.nextInt(3))).append(" Section ").append(section).append("\n\n");
            for (int paragraph = 0; paragraph < 1 + random.nextInt(4); paragraph++) {
                for (int sentence = 0; sentence < 1 + random.nextInt(8); sentence++) {
                    int words = 3 + random.nextInt(14);
                    text.append(IntStream.range(0, words).mapToObj(w -> "w" + random.nextInt(1000)).collect(Collectors.joining(" "))).append(". ");
                }
                text.append("\n\n");
            }
        }
        return text.toString();
    }
}
