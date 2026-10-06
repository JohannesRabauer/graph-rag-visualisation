package dev.rabauer.graphrag.core.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Tolerant JSON parsing for language-model replies.
 *
 * <p>Small local models (e.g. a 3B model served by Ollama) asked to "respond with
 * strict JSON" routinely wrap the JSON in prose or markdown fences, leave trailing
 * commas, use single quotes or unquoted keys, or stop mid-document when they hit
 * the token limit. This class extracts the first JSON value from such a reply and
 * repairs it as far as that can be done without guessing content:
 *
 * <ul>
 *   <li>{@code ```} / {@code ```json} fences are stripped, leading prose is skipped
 *       and trailing prose is ignored (none of this counts as a repair);</li>
 *   <li>trailing (and duplicated) commas in objects and arrays are dropped, and a
 *       missing comma between two members or elements is tolerated;</li>
 *   <li>single-quoted strings and unquoted keys made of {@code [A-Za-z0-9_$-]} are
 *       accepted, as are raw control characters (e.g. newlines) inside strings;</li>
 *   <li>a key followed by a colon but no value ({@code "a": ,}) is dropped;</li>
 *   <li>truncated input is closed: an unterminated string is closed, a dangling key
 *       without value is dropped, an incomplete literal ({@code tru}) or number
 *       ({@code 1.}, {@code -}) is dropped, and all open arrays/objects are closed in
 *       stack order.</li>
 * </ul>
 *
 * <p>Parsed values are {@link Map Map&lt;String, Object&gt;} (a {@link LinkedHashMap},
 * key order kept), {@link List List&lt;Object&gt;}, {@link String}, {@link Long}
 * (integral numbers that fit), {@link Double}, {@link Boolean}, or {@link #NULL} for
 * a JSON {@code null}. Nesting deeper than {@value #MAX_DEPTH} levels yields an empty
 * result. The parsing methods never throw.
 */
public final class LenientJson {

    /** Sentinel for a JSON {@code null}; its {@link Object#toString()} is {@code "null"}. */
    public static final Object NULL = new JsonNull();

    /** Maximum nesting depth of arrays/objects; deeper input yields an empty result. */
    public static final int MAX_DEPTH = 512;

    /** How many '{' / '[' positions are tried before giving up on a reply. */
    private static final int MAX_CANDIDATES = 32;

    private static final String FENCE = "```";

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    /**
     * Result of a lenient parse.
     *
     * @param value    the parsed value, never {@code null} (a JSON {@code null} is {@link #NULL})
     * @param repaired {@code true} when anything had to be fixed syntactically (trailing or
     *                 missing commas, truncation closing, single-quoted strings, unquoted keys,
     *                 a missing value after a colon, an unknown escape); stripping fences or
     *                 prose does not count as a repair
     */
    public record Result(Object value, boolean repaired) {

        public Result {
            Objects.requireNonNull(value, "value");
        }

        /**
         * The value as a JSON object.
         *
         * @return the value if it is an object, otherwise an empty map
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> object() {
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }

    private LenientJson() {
    }

    /**
     * Parses the first JSON object ({@code {...}}) found in a raw model reply.
     *
     * @param raw the model reply, may be {@code null}
     * @return the parsed object (a {@link Map}), or empty when the reply contains no
     *         {@code '{'} or nothing usable; never throws
     */
    public static Optional<Result> parseObject(String raw) {
        return run(raw, true);
    }

    /**
     * Parses the first top-level JSON value starting with {@code '{'} or {@code '['}
     * (whichever comes first) found in a raw model reply, with the same tolerance as
     * {@link #parseObject(String)}.
     *
     * @param raw the model reply, may be {@code null}
     * @return the parsed object or array, or empty when nothing usable is found; never throws
     */
    public static Optional<Result> parse(String raw) {
        return run(raw, false);
    }

    /**
     * The canonical, strictly valid JSON text of {@link #parseObject(String)}, re-serialized
     * from the parsed value, so adapters with their own JSON library can parse it.
     *
     * @param raw the model reply, may be {@code null}
     * @return strict JSON text of the first object in the reply, or empty; never throws
     */
    public static Optional<String> extractObjectText(String raw) {
        return parseObject(raw).map(result -> toJson(result.value()));
    }

    /**
     * Serializes a value tree of the types produced by this class back to strict JSON
     * (RFC 8259). {@code null} and {@link #NULL} become {@code null}; any other
     * {@link Number} is written as a number (non-finite ones as {@code null}); any other
     * {@link Iterable} or array as an array; any other object as its {@code toString()}
     * string.
     *
     * @param value the value tree
     * @return the JSON text
     */
    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /**
     * Reads a text value.
     *
     * @param object the object, may be {@code null}
     * @param key    the key
     * @return the trimmed string value, a number or boolean as text, otherwise {@code ""}
     */
    public static String string(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        return scalarText(value);
    }

    /**
     * Reads a list of texts.
     *
     * @param object the object, may be {@code null}
     * @param key    the key
     * @return the array's strings (trimmed; numbers and booleans as text; blanks, nulls and
     *         nested arrays/objects skipped); a single string becomes a one-element list;
     *         a missing key yields an empty list. The list is unmodifiable.
     */
    public static List<String> strings(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        List<String> texts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                String text = scalarText(element);
                if (!text.isEmpty()) {
                    texts.add(text);
                }
            }
        } else {
            String text = scalarText(value);
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return Collections.unmodifiableList(texts);
    }

    /**
     * Reads a list of objects.
     *
     * @param object the object, may be {@code null}
     * @param key    the key
     * @return the array's elements that are objects; a single object becomes a one-element
     *         list; anything else yields an empty list. The list is unmodifiable.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        List<Map<String, Object>> objects = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    objects.add((Map<String, Object>) map);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            objects.add((Map<String, Object>) map);
        }
        return Collections.unmodifiableList(objects);
    }

    /**
     * Reads a number.
     *
     * @param object the object, may be {@code null}
     * @param key    the key
     * @return the numeric value, also parsed from a numeric string; empty otherwise
     */
    public static OptionalDouble number(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (NUMBER.matcher(trimmed).matches()) {
                double d = Double.parseDouble(trimmed);
                return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    // ------------------------------------------------------------------ extraction

    private static Optional<Result> run(String raw, boolean objectOnly) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            String fenced = fencedContent(raw);
            if (fenced != null) {
                Optional<Result> inFence = scan(fenced, objectOnly);
                if (inFence.isPresent()) {
                    return inFence;
                }
            }
            return scan(raw, objectOnly);
        } catch (RuntimeException e) {
            // DepthExceeded, or anything unforeseen: this class never throws.
            return Optional.empty();
        }
    }

    /** Content of the first markdown fence, without the language tag; {@code null} if none. */
    private static String fencedContent(String raw) {
        int open = raw.indexOf(FENCE);
        if (open < 0) {
            return null;
        }
        int start = open + FENCE.length();
        while (start < raw.length() && isFenceTagChar(raw.charAt(start))) {
            start++;
        }
        int close = raw.indexOf(FENCE, start);
        return close < 0 ? raw.substring(start) : raw.substring(start, close);
    }

    private static boolean isFenceTagChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '_' || c == '-' || c == '+';
    }

    private static Optional<Result> scan(String text, boolean objectOnly) {
        int from = 0;
        for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
            int start = nextStart(text, from, objectOnly);
            if (start < 0) {
                return Optional.empty();
            }
            Parser parser = new Parser(text, start);
            try {
                Object value = parser.value();
                if (value != Parser.MISSING) {
                    return Optional.of(new Result(value, parser.repaired));
                }
            } catch (Fail notJsonHere) {
                // e.g. "{the}" in leading prose: try the next candidate
            }
            from = start + 1;
        }
        return Optional.empty();
    }

    private static int nextStart(String text, int from, boolean objectOnly) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || !objectOnly && c == '[') {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ parser

    /** Unrecoverable syntax error at a candidate position (cheap: no stack trace). */
    private static class Fail extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Fail() {
            super(null, null, false, false);
        }
    }

    /** Nesting too deep: aborts the whole parse rather than trying further candidates. */
    private static final class DepthExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DepthExceeded() {
            super(null, null, false, false);
        }
    }

    private static final Fail FAIL = new Fail();

    private static final class Parser {

        /** A value cut off by the end of input that cannot be completed; dropped by the caller. */
        static final Object MISSING = new Object();

        private final String s;
        private final int len;
        private int pos;
        private int depth;
        private boolean lastStringTruncated;
        boolean repaired;

        Parser(String s, int start) {
            this.s = s;
            this.len = s.length();
            this.pos = start;
        }

        Object value() {
            skipWhitespace();
            if (pos >= len) {
                repaired = true;
                return MISSING;
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"' || c == '\'') {
                return string();
            }
            if (c == '-' || c >= '0' && c <= '9') {
                return number();
            }
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                return literal();
            }
            throw FAIL;
        }

        private Map<String, Object> object() {
            enter();
            pos++;
            Map<String, Object> members = new LinkedHashMap<>();
            boolean pendingComma = false;
            boolean sawMember = false;
            while (true) {
                skipWhitespace();
                if (pos >= len) {
                    return truncated(members);
                }
                char c = s.charAt(pos);
                if (c == '}') {
                    if (pendingComma) {
                        repaired = true;
                    }
                    pos++;
                    depth--;
                    return members;
                }
                if (c == ',') {
                    if (pendingComma || !sawMember) {
                        repaired = true;
                    }
                    pendingComma = true;
                    pos++;
                    continue;
                }
                if (sawMember && !pendingComma) {
                    repaired = true; // missing comma between members
                }
                String key;
                if (c == '"' || c == '\'') {
                    key = string();
                    if (lastStringTruncated) {
                        return truncated(members);
                    }
                } else if (isKeyChar(c)) {
                    int start = pos;
                    while (pos < len && isKeyChar(s.charAt(pos))) {
                        pos++;
                    }
                    key = s.substring(start, pos);
                    repaired = true;
                } else {
                    throw FAIL;
                }
                skipWhitespace();
                if (pos >= len) {
                    return truncated(members);
                }
                if (s.charAt(pos) != ':') {
                    throw FAIL;
                }
                pos++;
                skipWhitespace();
                if (pos >= len) {
                    return truncated(members);
                }
                sawMember = true;
                pendingComma = false;
                c = s.charAt(pos);
                if (c == ',' || c == '}') {
                    repaired = true; // "key": without a value: drop the key
                    continue;
                }
                Object value = value();
                if (value == MISSING) {
                    return truncated(members);
                }
                members.put(key, value);
            }
        }

        private List<Object> array() {
            enter();
            pos++;
            List<Object> elements = new ArrayList<>();
            boolean pendingComma = false;
            boolean sawElement = false;
            while (true) {
                skipWhitespace();
                if (pos >= len) {
                    return truncated(elements);
                }
                char c = s.charAt(pos);
                if (c == ']') {
                    if (pendingComma) {
                        repaired = true;
                    }
                    pos++;
                    depth--;
                    return elements;
                }
                if (c == ',') {
                    if (pendingComma || !sawElement) {
                        repaired = true;
                    }
                    pendingComma = true;
                    pos++;
                    continue;
                }
                if (sawElement && !pendingComma) {
                    repaired = true; // missing comma between elements
                }
                Object value = value();
                if (value == MISSING) {
                    return truncated(elements);
                }
                elements.add(value);
                sawElement = true;
                pendingComma = false;
            }
        }

        private String string() {
            char quote = s.charAt(pos++);
            if (quote == '\'') {
                repaired = true;
            }
            StringBuilder out = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == quote) {
                    lastStringTruncated = false;
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c); // raw control characters are accepted
                    continue;
                }
                if (pos >= len) {
                    break; // dangling backslash at the token limit
                }
                char escape = s.charAt(pos++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        int available = Math.min(4, len - pos);
                        int code = 0;
                        boolean hex = true;
                        for (int i = 0; i < available && hex; i++) {
                            int digit = Character.digit(s.charAt(pos + i), 16);
                            hex = digit >= 0;
                            code = code * 16 + digit;
                        }
                        if (hex && available < 4) {
                            pos = len; // escape cut off by the token limit: drop it
                        } else if (hex) {
                            out.append((char) code);
                            pos += 4;
                        } else {
                            out.append('u');
                            repaired = true;
                        }
                    }
                    default -> {
                        out.append(escape); // e.g. \' inside a single-quoted string
                        repaired = true;
                    }
                }
            }
            repaired = true;
            lastStringTruncated = true;
            return out.toString();
        }

        private Object number() {
            int start = pos;
            while (pos < len && "+-.eE0123456789".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String token = s.substring(start, pos);
            if (!NUMBER.matcher(token).matches()) {
                if (pos >= len) {
                    repaired = true;
                    return MISSING; // e.g. "1." or "-" at the token limit
                }
                throw FAIL;
            }
            boolean integral = token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0;
            if (integral) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException tooBig) {
                    // falls through to double
                }
            }
            return Double.parseDouble(token);
        }

        private Object literal() {
            int start = pos;
            while (pos < len && Character.isLetter(s.charAt(pos))) {
                pos++;
            }
            String word = s.substring(start, pos);
            switch (word) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "null":
                    return NULL;
                default:
                    if (pos >= len && ("true".startsWith(word) || "false".startsWith(word)
                            || "null".startsWith(word))) {
                        repaired = true;
                        return MISSING; // e.g. "tru" at the token limit
                    }
                    throw FAIL;
            }
        }

        private <T> T truncated(T container) {
            repaired = true;
            depth--;
            return container;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw new DepthExceeded();
            }
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c <= ' ' || c == ' ' || c == '﻿' || Character.isWhitespace(c)) {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private static boolean isKeyChar(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '$' || c == '-';
        }
    }

    // ------------------------------------------------------------------ serialization

    private static void write(StringBuilder out, Object value) {
        if (value == null || value == NULL) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            out.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (value instanceof Number number) {
            String text = number.toString();
            out.append(NUMBER.matcher(text).matches() ? text : "null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, element);
            }
            out.append(']');
        } else if (value instanceof Object[] array) {
            write(out, Arrays.asList(array));
        } else {
            quote(out, value.toString());
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean loneSurrogate = Character.isHighSurrogate(c)
                            ? i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))
                            : Character.isLowSurrogate(c)
                                    && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)));
                    if (c < 0x20 || loneSurrogate) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String scalarText(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Long || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return "";
    }

    private static final class JsonNull {

        @Override
        public String toString() {
            return "null";
        }
    }
}
