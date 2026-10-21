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
        assertThat(DocumentChunker.version(DocumentFormat.MARKDOWN)).isEqualTo("markdown/4");
        assertThat(DocumentChunker.version(DocumentFormat.TEXT)).isEqualTo("text/4");
    }

    // Release 4: every chunk is bounded (ADR-0009)

    private final DocumentChunker defaults = new DocumentChunker(180, 30);

    @Test
    void cutsASentenceLongerThanTheCharacterCapAtWhitespaceWithoutOverlap() {
        String sentence = ("verylongword ".repeat(800)).strip() + ".";
        String text = DocumentChunker.normalize(sentence);

        List<ChunkDraft> chunks = defaults.chunk(text, DocumentFormat.TEXT);

        assertThat(chunks).hasSizeGreaterThan(5).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(DocumentChunker.MAX_CHARS));
        assertCoversWithoutOverlap(text, chunks);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).doesNotStartWith(" ").doesNotEndWith(" ").startsWith("verylongword"));
    }

    @Test
    void cutsALongCodeBlockAfterLineBreaks() {
        String code = "```\n" + "let value = compute(input);\n".repeat(200) + "```";
        String text = DocumentChunker.normalize("# Code\n\n" + code);

        List<ChunkDraft> chunks = defaults.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(DocumentChunker.MAX_CHARS));
        assertThat(chunks.subList(0, chunks.size() - 1)).allSatisfy(c -> assertThat(c.content()).endsWith(";"));
        assertCoversWithoutOverlap(text, chunks);
    }

    @Test
    void cutsARunWithoutWhitespaceAtTheCap() {
        String text = DocumentChunker.normalize("x".repeat(5_000));

        List<ChunkDraft> chunks = defaults.chunk(text, DocumentFormat.TEXT);

        assertThat(chunks).extracting(c -> c.content().length()).containsExactly(2_000, 2_000, 1_000);
        assertCoversWithoutOverlap(text, chunks);
    }

    @Test
    void packsNoMoreThanTheCharacterCapEvenWithinTheWordLimit() {
        String paragraphs = String.join("\n\n", java.util.Collections.nCopies(30, "a".repeat(120) + " b."));
        String text = DocumentChunker.normalize(paragraphs);

        assertThat(defaults.chunk(text, DocumentFormat.TEXT)).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(DocumentChunker.MAX_CHARS));
    }

    @Test
    void embedsTheInnermostHeadingsWithinTheHeadingLimit() {
        String outer = "Outer " + "o".repeat(190);
        String path = outer + " > Middle section > Inner";

        assertThat(DocumentChunker.withContext(path, "Body.")).isEqualTo("Middle section > Inner\n\nBody.");
        assertThat(DocumentChunker.embeddedPath("h".repeat(300))).hasSize(DocumentChunker.HEADING_MAX_CHARS);
        assertThat(DocumentChunker.embeddedPath("Short > Path")).isEqualTo("Short > Path");
    }

    @Test
    void splitsAChunkWhoseEmbeddingInputDoesNotFitTheTokenBudgetWhereItStopsFitting() {
        // 150 words of 20 characters: within 180 words and 2,000 characters per piece, but over budget for a tokenizer that counts characters
        String text = DocumentChunker.normalize(String.join(" ", java.util.Collections.nCopies(150, "abcdefghijklmnopqrs.")));
        DocumentChunker.TokenFitter oneTokenPerCharacter = (inputs, budget) -> inputs.stream().map(i -> Math.min(i.length(), budget - 2)).toList();

        List<ChunkDraft> fitted = DocumentChunker.fit(defaults.chunk(text, DocumentFormat.TEXT), text, true, oneTokenPerCharacter);

        assertThat(fitted).hasSizeGreaterThan(1)
                .allSatisfy(c -> assertThat(c.content().length() + 2).isLessThanOrEqualTo(DocumentChunker.MAX_TOKENS))
                .extracting(ChunkDraft::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, fitted.size()).boxed().toList());
        assertThat(fitted).allSatisfy(c -> assertThat(c.content()).startsWith("abcdefghijklmnopqrs."));
        assertCoversWithoutOverlap(text, fitted);
    }

    @Test
    void leavesChunksThatFitUntouched() {
        String text = DocumentChunker.normalize("# Title\n\nShort paragraph.\n\nAnother one.");
        List<ChunkDraft> chunks = defaults.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(DocumentChunker.fit(chunks, text, true, (inputs, budget) -> inputs.stream().map(String::length).toList())).isEqualTo(chunks);
    }

    // Characters outside the Basic Multilingual Plane are two UTF-16 units in Java and one code point on the wire (review 2026-10-21, N2)

    /** What the model service answers: the fitting prefix in Unicode code points. */
    private static final DocumentChunker.TokenFitter CODE_POINTS_FIT_EVERYTHING = (inputs, budget) -> inputs.stream()
            .map(input -> input.codePointCount(0, input.length()))
            .toList();

    @Test
    void anInputThatFitsIsNotSplitWhenItContainsCharactersOutsideTheBasicPlane() {
        String text = DocumentChunker.normalize("Launch \uD83D\uDE00 approved for \uD840\uDC0B.");
        List<ChunkDraft> chunks = defaults.chunk(text, DocumentFormat.TEXT);

        List<ChunkDraft> fitted = DocumentChunker.fit(chunks, text, true, CODE_POINTS_FIT_EVERYTHING);

        assertThat(fitted).isEqualTo(chunks);
        assertThat(fitted).allSatisfy(c -> assertThat(hasLoneSurrogate(c.content())).isFalse());
    }

    @Test
    void aSplitWhereTheFittingPrefixEndsCountsCodePoints() {
        // Ten emoji, one token each, a budget of 2 special tokens + 4: the prefix ends after the fourth emoji, eight UTF-16 units in
        String text = DocumentChunker.normalize("\uD83D\uDE00".repeat(10));
        DocumentChunker.TokenFitter fourEmoji = (inputs, budget) -> inputs.stream().map(input -> Math.min(input.codePointCount(0, input.length()), 4)).toList();

        List<ChunkDraft> fitted = DocumentChunker.fit(defaults.chunk(text, DocumentFormat.TEXT), text, false, fourEmoji);

        assertThat(fitted).extracting(c -> c.content().codePointCount(0, c.content().length())).containsExactly(4, 4, 2);
        assertThat(fitted).allSatisfy(c -> assertThat(hasLoneSurrogate(c.content())).isFalse());
        assertCoversWithoutOverlap(text, fitted);
    }

    @Test
    void theCharacterCapNeverSplitsASurrogatePair() {
        String text = DocumentChunker.normalize("a".repeat(DocumentChunker.MAX_CHARS - 1) + "\uD83D\uDE00" + "b".repeat(500));

        List<ChunkDraft> chunks = defaults.chunk(text, DocumentFormat.TEXT);

        assertThat(chunks).allSatisfy(c -> assertThat(hasLoneSurrogate(c.content())).isFalse());
        assertCoversWithoutOverlap(text, chunks);
    }

    @Test
    void theHeadingLimitNeverSplitsASurrogatePair() {
        String heading = "h".repeat(DocumentChunker.HEADING_MAX_CHARS - 1) + "\uD83D\uDE00";

        assertThat(hasLoneSurrogate(DocumentChunker.embeddedPath(heading))).isFalse();
        assertThat(DocumentChunker.embeddedPath(heading)).hasSize(DocumentChunker.HEADING_MAX_CHARS - 1);
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)))) {
                return true;
            }
            if (Character.isHighSurrogate(c)) {
                i++;
            }
        }
        return false;
    }

    /**
     * Consecutive chunks follow each other: whatever lies between them is whitespace, and they never overlap. Offsets match the content.
     */
    private static void assertCoversWithoutOverlap(String text, List<ChunkDraft> chunks) {
        assertThat(text.substring(0, chunks.getFirst().charStart()).replaceAll("(?m)^#+ .*$", "")).isBlank();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            assertThat(text.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(chunk.content());
            if (i > 0) {
                assertThat(chunk.charStart()).isGreaterThanOrEqualTo(chunks.get(i - 1).charEnd());
                assertThat(text.substring(chunks.get(i - 1).charEnd(), chunk.charStart())).isBlank();
            }
        }
        assertThat(text.substring(chunks.getLast().charEnd())).isBlank();
    }

    @Test
    void theEmbeddingInputPutsTheHeadingPathBeforeTheChunkAndLeavesPlainTextAlone() {
        assertThat(DocumentChunker.withContext("Runbook > Launch gates", "Borealis launches after 30 days.")).isEqualTo("Runbook > Launch gates\n\nBorealis launches after 30 days.");
        assertThat(DocumentChunker.withContext("", "No headings here.")).isEqualTo("No headings here.");
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
