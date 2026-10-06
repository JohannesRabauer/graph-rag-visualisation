package dev.rabauer.graphrag.core.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a graph element or passage lives in its source: a path and an
 * optional 1-based, inclusive line range, rendered as
 * {@code path:startLine-endLine} (for example
 * {@code src/main/java/com/acme/OrderService.java:42-57}).
 *
 * <p>Line numbers are 1-based; {@code 0} means "unknown". A locator without
 * lines points at the whole source. The compact constructor normalises: a
 * null path becomes {@code ""}, a negative line becomes {@code 0}, and an end
 * line before the start line becomes the start line.
 *
 * @param path      the source path (a file path, URL or document name); never null
 * @param startLine the first line, 1-based; {@code 0} when unknown
 * @param endLine   the last line, inclusive; {@code 0} when unknown
 */
public record SourceLocator(String path, int startLine, int endLine) {

    private static final Pattern FORMATTED = Pattern.compile("^(.*?)(?::(\\d+)(?:-(\\d+))?)?$");

    public SourceLocator {
        path = path == null ? "" : path.trim();
        startLine = Math.max(0, startLine);
        endLine = Math.max(0, endLine);
        if (startLine == 0) {
            endLine = 0;
        } else if (endLine < startLine) {
            endLine = startLine;
        }
    }

    /** A locator for a whole source, without lines. */
    public static SourceLocator of(String path) {
        return new SourceLocator(path, 0, 0);
    }

    /** A locator for one line. */
    public static SourceLocator of(String path, int line) {
        return new SourceLocator(path, line, line);
    }

    /** A locator for a 1-based, inclusive line range. */
    public static SourceLocator of(String path, int startLine, int endLine) {
        return new SourceLocator(path, startLine, endLine);
    }

    /**
     * Parses {@link #format()}'s output: {@code path}, {@code path:12} or
     * {@code path:12-20}. Empty for a null or blank text.
     */
    public static Optional<SourceLocator> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = FORMATTED.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.of(of(text));
        }
        String path = matcher.group(1);
        if (matcher.group(2) == null) {
            return Optional.of(of(path));
        }
        int start = parseLine(matcher.group(2));
        int end = matcher.group(3) == null ? start : parseLine(matcher.group(3));
        return Optional.of(new SourceLocator(path, start, end));
    }

    /** Whether this locator carries a line range. */
    public boolean hasLines() {
        return startLine > 0;
    }

    /** {@code path}, {@code path:12} for a single line, or {@code path:12-20}. */
    public String format() {
        if (!hasLines()) {
            return path;
        }
        return startLine == endLine ? path + ":" + startLine : path + ":" + startLine + "-" + endLine;
    }

    @Override
    public String toString() {
        return format();
    }

    private static int parseLine(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
