package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.domain.UploadedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits each document of a {@link Corpus} into overlapping {@link TextUnit}s
 * of about {@link #TARGET_CHARS} characters, sharing about
 * {@link #OVERLAP_CHARS} characters with their predecessor. A unit is cut on
 * the last paragraph break inside its window, else on the last sentence end,
 * else on the last whitespace, else hard at the window's end. Deterministic
 * and framework-free; deliberately separate from the Vector Baseline's
 * chunker in {@link ConstructVectorIndex}.
 */
public final class TextUnitSplitter {

    public static final int TARGET_CHARS = 6000;
    public static final int OVERLAP_CHARS = 600;

    /**
     * A boundary closer to the window start than this would make units
     * needlessly small, so it is ignored in favor of a weaker boundary.
     */
    private static final int MIN_CUT_CHARS = TARGET_CHARS / 2;

    private TextUnitSplitter() {
    }

    /**
     * @return every Text Unit of the corpus, document by document, in order;
     *         blank documents contribute no units
     */
    public static List<TextUnit> split(Corpus corpus) {
        List<TextUnit> units = new ArrayList<>();
        if (corpus == null || corpus.documents() == null) {
            return units;
        }
        List<UploadedDocument> documents = corpus.documents();
        for (int documentIndex = 0; documentIndex < documents.size(); documentIndex++) {
            UploadedDocument document = documents.get(documentIndex);
            if (document == null || document.content() == null || document.content().isBlank()) {
                continue;
            }
            int ordinal = 0;
            for (String text : splitText(document.content())) {
                String id = corpus.id() + "::doc-" + documentIndex + "::tu-" + ordinal;
                units.add(new TextUnit(id, corpus.id(), document.filename(), ordinal, text));
                ordinal++;
            }
        }
        return units;
    }

    static List<String> splitText(String text) {
        List<String> pieces = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            int windowEnd = start + TARGET_CHARS;
            if (windowEnd >= length) {
                pieces.add(text.substring(start));
                break;
            }
            int cut = findCut(text, start, windowEnd);
            pieces.add(text.substring(start, cut));
            start = nextStart(text, cut);
        }
        return pieces;
    }

    /** Returns the exclusive end of the unit starting at {@code start}. */
    private static int findCut(String text, int start, int windowEnd) {
        int minCut = start + MIN_CUT_CHARS;

        int lineFeedBreak = text.lastIndexOf("\n\n", windowEnd - 2);
        int crlfBreak = text.lastIndexOf("\r\n\r\n", windowEnd - 4);
        int paragraphEnd = Math.max(lineFeedBreak < 0 ? -1 : lineFeedBreak + 2, crlfBreak < 0 ? -1 : crlfBreak + 4);
        if (paragraphEnd > minCut) {
            return paragraphEnd;
        }

        for (int i = windowEnd - 1; i >= minCut; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }

        for (int i = windowEnd - 1; i >= minCut; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }

        return windowEnd;
    }

    /**
     * Steps back {@link #OVERLAP_CHARS} from the cut, then forward to the next
     * word start (if one exists before the cut) so a unit does not begin
     * mid-word.
     */
    private static int nextStart(String text, int cut) {
        int start = cut - OVERLAP_CHARS;
        if (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            for (int i = start; i < cut; i++) {
                if (Character.isWhitespace(text.charAt(i))) {
                    return i + 1;
                }
            }
        }
        return start;
    }
}
