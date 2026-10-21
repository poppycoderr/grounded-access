package io.groundedaccess.corpus;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits normalized text into chunks that never cross a Markdown heading. Whole paragraphs of one section are packed together up to
 * {@code maxWords}; a paragraph longer than that is packed sentence by sentence (list items count as sentences) and code blocks stay whole. When a
 * section needs several chunks, each chunk after the first starts with up to {@code overlapWords} of whole sentences from the end of the previous
 * one, so a fact stated across the boundary is still found in one chunk. The overlap counts towards {@code maxWords}.
 *
 * <p>
 * Release 4 bounds every chunk (ADR-0009). A sentence, code block or space-free run longer than {@link #MAX_CHARS} is cut at its last line
 * break, else its last whitespace, else at the limit, and no chunk packs more than that many characters. {@link #fit} then splits, the same way,
 * every chunk whose embedding input exceeds the models' token budget. Pieces cut from one oversized span do not overlap each other.
 *
 * <p>
 * Every chunk is one contiguous span of the normalized text, overlap included, so evaluation can map evidence quotes to chunks by offsets.
 */
public final class DocumentChunker {

    private static final String RELEASE = "4";

    /** No chunk is longer than this, before any token is counted. */
    public static final int MAX_CHARS = 2_000;

    /** Tokens of an embedding input, special tokens included; the rest of the models' 512 is the reranker's room for a query. */
    public static final int MAX_TOKENS = 384;

    /** Characters of the heading path that are embedded with a chunk; outer headings are dropped first. */
    public static final int HEADING_MAX_CHARS = 200;

    private static final String SEPARATOR = " > ";

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
     * The version recorded with every document version, so evaluation results say which chunking produced them. Whether chunks were embedded
     * with their heading path is part of the index profile.
     */
    public static String version(DocumentFormat format) {
        return format.column() + "/" + RELEASE;
    }

    /**
     * The release part of {@link #version}, the same for every format; part of the index profile.
     */
    static String release() {
        return RELEASE;
    }

    int maxWords() {
        return maxWords;
    }

    int overlapWords() {
        return overlapWords;
    }

    /**
     * What is embedded for a chunk when context is on: the heading path, a blank line, the chunk. A chunk often depends on its headings
     * ("Launch gates" above a sentence that never says "gate"), and a question tends to use the heading's words. The stored chunk and its
     * offsets do not change; only the text handed to the embedding model does.
     */
    public static String withContext(String sectionPath, String content) {
        String path = embeddedPath(sectionPath);
        return path.isEmpty() ? content : path + "\n\n" + content;
    }

    /**
     * The heading path as it is embedded: at most {@link #HEADING_MAX_CHARS} characters, keeping the innermost headings, which say most about
     * the chunk. A single heading longer than that is cut. The stored path is not changed.
     */
    static String embeddedPath(String sectionPath) {
        List<String> headings = new ArrayList<>(List.of(sectionPath.split(SEPARATOR, -1)));
        while (headings.size() > 1 && String.join(SEPARATOR, headings).length() > HEADING_MAX_CHARS) {
            headings.removeFirst();
        }
        String path = String.join(SEPARATOR, headings);
        return path.length() > HEADING_MAX_CHARS ? path.substring(0, notInsidePair(path, 0, HEADING_MAX_CHARS)) : path;
    }

    /**
     * The fitting prefix of each text within a token budget, as the models' tokenizers count it, in Unicode code points: the unit the model
     * service uses. A character outside the Basic Multilingual Plane is one code point but two Java chars.
     */
    @FunctionalInterface
    public interface TokenFitter {

        List<Integer> fittingChars(List<String> texts, int budget);
    }

    /**
     * Splits every chunk whose embedding input does not fit {@link #MAX_TOKENS} where its fitting prefix ends, at the last line break, else the
     * last whitespace, and checks the remainder again. Tokenizers are deterministic, so the same document always gets the same chunks.
     */
    public static List<ChunkDraft> fit(List<ChunkDraft> chunks, String text, boolean embeddingContext, TokenFitter fitter) {
        List<ChunkDraft> done = new ArrayList<>();
        List<ChunkDraft> pending = chunks;
        Map<ChunkDraft, List<ChunkDraft>> replaced = new IdentityHashMap<>();
        while (!pending.isEmpty()) {
            List<String> inputs = pending.stream().map(c -> embeddingContext ? withContext(c.sectionPath(), c.content()) : c.content()).toList();
            List<Integer> fitting = fitter.fittingChars(inputs, MAX_TOKENS);
            List<ChunkDraft> next = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                ChunkDraft chunk = pending.get(i);
                String input = inputs.get(i);
                int fits = fitting.get(i);
                int inputCodePoints = input.codePointCount(0, input.length());
                if (fits >= inputCodePoints) {
                    continue;
                }
                int contentCodePoints = chunk.content().codePointCount(0, chunk.content().length());
                int prefix = inputCodePoints - contentCodePoints;
                int fittingContent = Math.min(contentCodePoints - 1, Math.max(1, fits - prefix));
                int cut = cut(text, chunk.charStart(), text.offsetByCodePoints(chunk.charStart(), fittingContent));
                int rest = skipWhitespace(text, cut, chunk.charEnd());
                ChunkDraft head = draft(chunk.ordinal(), chunk.sectionPath(), text, chunk.charStart(), trimEnd(text, chunk.charStart(), cut));
                next.add(head);
                if (rest < chunk.charEnd()) {
                    ChunkDraft tail = draft(chunk.ordinal(), chunk.sectionPath(), text, rest, chunk.charEnd());
                    replaced.put(chunk, List.of(head, tail));
                    next.add(tail);
                } else {
                    replaced.put(chunk, List.of(head));
                }
            }
            pending = next;
        }
        for (ChunkDraft chunk : chunks) {
            expand(chunk, replaced, done);
        }
        List<ChunkDraft> numbered = new ArrayList<>(done.size());
        for (ChunkDraft chunk : done) {
            numbered.add(new ChunkDraft(numbered.size(), chunk.sectionPath(), chunk.charStart(), chunk.charEnd(), chunk.content(), chunk.tokenCount()));
        }
        return numbered;
    }

    private static void expand(ChunkDraft chunk, Map<ChunkDraft, List<ChunkDraft>> replaced, List<ChunkDraft> into) {
        List<ChunkDraft> parts = replaced.get(chunk);
        if (parts == null) {
            into.add(chunk);
        } else {
            parts.forEach(part -> expand(part, replaced, into));
        }
    }

    /**
     * Where to cut a span that must end at or before {@code limit}: after its last line break, else at its last whitespace, else at the limit,
     * moved back by one if the limit falls between the two halves of a surrogate pair.
     */
    private static int cut(String text, int start, int limit) {
        int newline = text.lastIndexOf('\n', limit - 1);
        if (newline > start) {
            return newline + 1;
        }
        for (int i = limit - 1; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return notInsidePair(text, start, limit);
    }

    /**
     * {@code position}, or one before it when it would separate a high surrogate from its low surrogate; never at or before {@code start}.
     */
    private static int notInsidePair(String text, int start, int position) {
        boolean inside = position > start && position < text.length() && Character.isLowSurrogate(text.charAt(position))
                && Character.isHighSurrogate(text.charAt(position - 1));
        return inside && position - 1 > start ? position - 1 : inside ? position + 1 : position;
    }

    private static int skipWhitespace(String text, int from, int end) {
        int position = from;
        while (position < end && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
        return position;
    }

    private static int trimEnd(String text, int start, int end) {
        int position = end;
        while (position > start + 1 && Character.isWhitespace(text.charAt(position - 1))) {
            position--;
        }
        return position;
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
     * A span of the normalized text. {@code atomic} spans, paragraphs containing a code fence, are never split into sentences. {@code cut} spans
     * are pieces of a span longer than {@link #MAX_CHARS}; no chunk overlaps into one.
     */
    private record Span(
            int start,

            int end,

            boolean atomic,

            boolean cut) {

        Span(int start, int end, boolean atomic) {
            this(start, end, atomic, false);
        }
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
            List<Span> split = paragraph.atomic() || words(text, paragraph) <= maxWords ? List.of(paragraph) : sentences(text, paragraph);
            for (Span piece : split) {
                pieces.addAll(piece.end() - piece.start() > MAX_CHARS ? cutToSize(text, piece) : List.of(piece));
            }
        }
        paragraphs.clear();

        int start = -1;
        int end = -1;
        boolean endsInCut = false;
        Span previous = null;
        for (Span piece : pieces) {
            if (start >= 0 && (countWords(text.substring(start, piece.end())) > maxWords || piece.end() - start > MAX_CHARS)) {
                previous = new Span(start, end, false, endsInCut);
                chunks.add(draft(chunks.size(), sectionPath, text, start, end));
                start = -1;
            }
            if (start < 0) {
                start = previous == null || previous.cut() || piece.cut() ? piece.start() : overlapStart(text, previous, piece.start());
                if (countWords(text.substring(start, piece.end())) > maxWords || piece.end() - start > MAX_CHARS) {
                    start = piece.start();
                }
            }
            end = piece.end();
            endsInCut = piece.cut();
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

    /**
     * Pieces of at most {@link #MAX_CHARS} characters: each ends after its last line break, else at its last whitespace, else at the limit.
     * Whitespace between two pieces belongs to neither.
     */
    private static List<Span> cutToSize(String text, Span span) {
        List<Span> pieces = new ArrayList<>();
        int start = span.start();
        while (span.end() - start > MAX_CHARS) {
            int end = cut(text, start, start + MAX_CHARS);
            pieces.add(new Span(start, trimEnd(text, start, end), true, true));
            start = skipWhitespace(text, end, span.end());
        }
        if (start < span.end()) {
            pieces.add(new Span(start, span.end(), true, true));
        }
        return pieces;
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
