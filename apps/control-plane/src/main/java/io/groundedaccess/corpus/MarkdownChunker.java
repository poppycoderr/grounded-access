package io.groundedaccess.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits normalized Markdown into chunks that never cross a heading. Paragraphs of one section are packed together up to {@code maxWords}; a single
 * paragraph longer than that stays whole. Chunk offsets point into the normalized text, which evaluation uses to map evidence quotes to chunks.
 */
public final class MarkdownChunker {

    public static final String VERSION = "markdown-headings/1";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private final int maxWords;

    public MarkdownChunker(int maxWords) {
        this.maxWords = maxWords;
    }

    public static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return (unified.startsWith("﻿") ? unified.substring(1) : unified).strip() + "\n";
    }

    public List<ChunkDraft> chunk(String normalized) {
        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<int[]> paragraphs = new ArrayList<>();
        int paragraphStart = -1;
        boolean inFence = false;
        int offset = 0;
        for (String line : normalized.split("\n", -1)) {
            int lineEnd = offset + line.length();
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
            }
            Matcher heading = inFence ? null : HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                paragraphStart = closeParagraph(paragraphs, paragraphStart, offset);
                flush(chunks, headings, paragraphs, normalized);
                int level = heading.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                while (headings.size() < level - 1) {
                    headings.add("");
                }
                headings.add(heading.group(2));
            } else if (line.isBlank() && !inFence) {
                paragraphStart = closeParagraph(paragraphs, paragraphStart, offset);
            } else if (paragraphStart < 0) {
                paragraphStart = offset;
            }
            offset = lineEnd + 1;
        }
        closeParagraph(paragraphs, paragraphStart, normalized.length());
        flush(chunks, headings, paragraphs, normalized);
        return chunks;
    }

    private static int closeParagraph(List<int[]> paragraphs, int start, int end) {
        if (start >= 0) {
            paragraphs.add(new int[] {start, end});
        }
        return -1;
    }

    private void flush(List<ChunkDraft> chunks, List<String> headings, List<int[]> paragraphs, String text) {
        String sectionPath = String.join(" > ", headings.stream().filter(h -> !h.isEmpty()).toList());
        int start = -1;
        int end = -1;
        int words = 0;
        for (int[] paragraph : paragraphs) {
            String body = text.substring(paragraph[0], paragraph[1]).strip();
            int paragraphWords = countWords(body);
            if (start >= 0 && words + paragraphWords > maxWords) {
                chunks.add(draft(chunks.size(), sectionPath, text, start, end, words));
                start = -1;
                words = 0;
            }
            if (start < 0) {
                start = paragraph[0];
            }
            end = paragraph[0] + text.substring(paragraph[0], paragraph[1]).stripTrailing().length();
            words += paragraphWords;
        }
        if (start >= 0) {
            chunks.add(draft(chunks.size(), sectionPath, text, start, end, words));
        }
        paragraphs.clear();
    }

    private static ChunkDraft draft(int ordinal, String sectionPath, String text, int start, int end, int words) {
        return new ChunkDraft(ordinal, sectionPath, start, end, text.substring(start, end), words);
    }

    static int countWords(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
    }
}
