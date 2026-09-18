package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(12);

    @Test
    void chunksNeverCrossHeadingsAndCarryTheHeadingPath() {
        String text = MarkdownChunker.normalize("""
                # Volunteer Policy

                Intro paragraph.

                ## European Union

                EU employees receive two paid volunteer days.

                ## United States

                US employees receive one paid volunteer day.
                """);

        List<ChunkDraft> chunks = chunker.chunk(text);

        assertThat(chunks).extracting(ChunkDraft::sectionPath)
                .containsExactly("Volunteer Policy", "Volunteer Policy > European Union", "Volunteer Policy > United States");
        assertThat(chunks.get(1).content()).isEqualTo("EU employees receive two paid volunteer days.");
    }

    @Test
    void offsetsPointIntoTheNormalizedText() {
        String text = MarkdownChunker.normalize("# A\r\n\r\nfirst paragraph here\r\n\r\nsecond one\r\n");

        for (ChunkDraft chunk : chunker.chunk(text)) {
            assertThat(text.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(chunk.content());
        }
    }

    @Test
    void packsParagraphsUpToTheWordLimitAndKeepsLongParagraphsWhole() {
        String text = MarkdownChunker.normalize("""
                one two three four

                five six seven eight

                nine ten eleven twelve thirteen

                a b c d e f g h i j k l m n o p
                """);

        List<ChunkDraft> chunks = chunker.chunk(text);

        assertThat(chunks).extracting(ChunkDraft::tokenCount).containsExactly(8, 5, 16);
        assertThat(chunks.getFirst().content()).isEqualTo("one two three four\n\nfive six seven eight");
    }

    @Test
    void ignoresHeadingSyntaxInsideCodeFences() {
        String text = MarkdownChunker.normalize("""
                # Runbook

                ```bash
                # not a heading
                pg_ctl promote
                ```
                """);

        List<ChunkDraft> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().sectionPath()).isEqualTo("Runbook");
        assertThat(chunks.getFirst().content()).contains("# not a heading");
    }

    @Test
    void normalizationIsStableForHashing() {
        assertThat(MarkdownChunker.normalize("﻿  text\r\n\r\n")).isEqualTo("text\n");
    }
}
