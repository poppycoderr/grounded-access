package io.groundedaccess.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits normalized text into chunks that never cross a Markdown heading. Whole paragraphs of one section are packed together up to
 * {@code maxWords}; a paragraph longer than that is packed sentence by sentence (list items count as sentences) and code blocks stay whole. When a section needs several chunks, each chunk after
 * the first starts with up to {@code overlapWords} of whole sentences from the end of the previous one, so a fact stated across the boundary is
 * still found in one chunk. The overlap counts towards {@code maxWords}. A single sentence longer than the limit stays whole.
 *
 * <p>Every chunk is one contiguous span of the normalized text, overlap included, so evaluation can map evidence quotes to chunks by offsets.
 */
public final class DocumentChunker {

    private static final String RELEASE = "2";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** A sentence ends at terminal punctuation (plus closing quotes or brackets) followed by whitespace, or before a new list item. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?][\"')\\]]{0,2})\\s+|\\n(?=[ \\t]*(?:[-*+]|\\d+[.)])[ \\t])");

    private final int maxWords;

    private final int overlapWords;

    public DocumentChunker(int maxWords, int overlapWords) {
        if (overlapWords < 0 || overlapWords >= maxWords) {
            throw new IllegalArgumentException("overlapWords must be at least 0 and below maxWords");
        }
        this.maxWords = maxWords;
        this.overlapWords = overlapWords;
    }

    /**
     * The version recorded with every document version, so evaluation results say which chunking produced them.
     */
    public static String version(DocumentFormat format) {
        return format.column() + "/" + RELEASE;
    }

    public static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return (unified.startsWith("﻿") ? unified.substring(1) : unified).strip() + "\n";
    }

    public List<ChunkDraft> chunk(String normalized, DocumentFormat format) {
        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<Span> paragraphs = new ArrayList<>();
        boolean markdown = format == DocumentFormat.MARKDOWN;
        int paragraphStart = -1;
        boolean inFence = false;
        boolean paragraphHasFence = false;
        int offset = 0;
        for (String line : normalized.split("\n", -1)) {
            int lineEnd = offset + line.length();
            boolean fenceLine = markdown && line.strip().startsWith("```");
            if (fenceLine) {
                inFence = !inFence;
            }
            Matcher heading = markdown && !inFence && !fenceLine ? HEADING.matcher(line) : null;
            if (heading != null && heading.matches()) {
                paragraphStart = close(paragraphs, paragraphStart, offset, normalized, paragraphHasFence);
                section(chunks, headings, paragraphs, normalized);
                int level = heading.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                while (headings.size() < level - 1) {
                    headings.add("");
                }
                headings.add(heading.group(2));
            } else if (line.isBlank() && !inFence) {
                paragraphStart = close(paragraphs, paragraphStart, offset, normalized, paragraphHasFence);
            } else {
                if (paragraphStart < 0) {
                    paragraphStart = offset;
                    paragraphHasFence = false;
                }
                paragraphHasFence |= fenceLine;
            }
            offset = lineEnd + 1;
        }
        close(paragraphs, paragraphStart, normalized.length(), normalized, paragraphHasFence);
        section(chunks, headings, paragraphs, normalized);
        return chunks;
    }

    /**
     * A span of the normalized text. {@code atomic} spans, paragraphs containing a code fence, are never split into sentences.
     */
    private record Span(
            int start,

            int end,

            boolean atomic) {
    }

    private static int close(List<Span> paragraphs, int start, int end, String text, boolean atomic) {
        if (start >= 0) {
            int trimmedEnd = start + text.substring(start, end).stripTrailing().length();
            if (trimmedEnd > start) {
                paragraphs.add(new Span(start, trimmedEnd, atomic));
            }
        }
        return -1;
    }

    private void section(List<ChunkDraft> chunks, List<String> headings, List<Span> paragraphs, String text) {
        String sectionPath = String.join(" > ", headings.stream().filter(h -> !h.isEmpty()).toList());
        List<Span> pieces = new ArrayList<>();
        for (Span paragraph : paragraphs) {
            if (paragraph.atomic() || words(text, paragraph) <= maxWords) {
                pieces.add(paragraph);
            } else {
                pieces.addAll(sentences(text, paragraph));
            }
        }
        paragraphs.clear();

        int start = -1;
        int end = -1;
        Span previous = null;
        for (Span piece : pieces) {
            if (start >= 0 && countWords(text.substring(start, piece.end())) > maxWords) {
                previous = new Span(start, end, false);
                chunks.add(draft(chunks.size(), sectionPath, text, start, end));
                start = -1;
            }
            if (start < 0) {
                start = previous == null ? piece.start() : overlapStart(text, previous, piece.start());
                if (countWords(text.substring(start, piece.end())) > maxWords) {
                    start = piece.start();
                }
            }
            end = piece.end();
        }
        if (start >= 0) {
            chunks.add(draft(chunks.size(), sectionPath, text, start, end));
        }
    }

    /**
     * Where a chunk that follows {@code previous} begins: at the earliest sentence of the previous chunk's tail that still fits the overlap, or at
     * {@code next} when not even its last sentence fits.
     */
    private int overlapStart(String text, Span previous, int next) {
        List<Span> tail = sentences(text, previous);
        int start = next;
        int words = 0;
        for (int i = tail.size() - 1; i >= 0; i--) {
            Span sentence = tail.get(i);
            words += words(text, sentence);
            if (words > overlapWords) {
                break;
            }
            start = sentence.start();
        }
        return start;
    }

    private static List<Span> sentences(String text, Span span) {
        List<Span> sentences = new ArrayList<>();
        Matcher breaks = SENTENCE_BREAK.matcher(text).region(span.start(), span.end());
        int start = span.start();
        while (breaks.find()) {
            if (breaks.start() > start) {
                sentences.add(new Span(start, breaks.start(), false));
            }
            start = breaks.end();
        }
        if (start < span.end()) {
            sentences.add(new Span(start, span.end(), false));
        }
        return sentences;
    }

    private static int words(String text, Span span) {
        return countWords(text.substring(span.start(), span.end()));
    }

    private static ChunkDraft draft(int ordinal, String sectionPath, String text, int start, int end) {
        String content = text.substring(start, end);
        return new ChunkDraft(ordinal, sectionPath, start, end, content, countWords(content));
    }

    static int countWords(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
    }
}
