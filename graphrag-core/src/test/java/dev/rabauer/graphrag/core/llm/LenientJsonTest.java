package dev.rabauer.graphrag.core.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LenientJsonTest {

    private static Map<String, Object> object(String raw) {
        return LenientJson.parseObject(raw).orElseThrow().object();
    }

    private static boolean repaired(String raw) {
        return LenientJson.parseObject(raw).orElseThrow().repaired();
    }

    // ------------------------------------------------------------------ strict input

    @Test
    void parsesStrictJsonWithoutReportingARepair() {
        Optional<LenientJson.Result> result = LenientJson.parseObject(
                "{\"s\":\"x\",\"i\":42,\"d\":1.5,\"e\":2e3,\"t\":true,\"f\":false,\"n\":null,\"a\":[1,\"b\"],\"o\":{}}");

        assertTrue(result.isPresent());
        assertFalse(result.get().repaired());
        Map<String, Object> o = result.get().object();
        assertEquals("x", o.get("s"));
        assertEquals(42L, o.get("i"));
        assertEquals(1.5, o.get("d"));
        assertEquals(2000.0, o.get("e"));
        assertEquals(Boolean.TRUE, o.get("t"));
        assertEquals(Boolean.FALSE, o.get("f"));
        assertSame(LenientJson.NULL, o.get("n"));
        assertEquals(List.of(1L, "b"), o.get("a"));
        assertEquals(Map.of(), o.get("o"));
    }

    @Test
    void keepsTheKeyOrderOfTheReply() {
        Map<String, Object> o = object("{\"z\":1,\"a\":2,\"m\":3}");

        assertInstanceOf(LinkedHashMap.class, o);
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(o.keySet()));
    }

    @Test
    void nullSentinelPrintsAsNull() {
        assertEquals("null", LenientJson.NULL.toString());
    }

    @Test
    void integralNumbersTooLargeForALongBecomeDoubles() {
        Map<String, Object> o = object("{\"big\":123456789012345678901234567890,\"neg\":-7}");

        assertInstanceOf(Double.class, o.get("big"));
        assertEquals(-7L, o.get("neg"));
    }

    // ------------------------------------------------------------------ prose and fences

    @Test
    void stripsMarkdownFencesAroundTheObject() {
        Optional<LenientJson.Result> result = LenientJson.parseObject(
                "```json\n{\"title\": \"Order handling\"}\n```");

        assertEquals("Order handling", result.orElseThrow().object().get("title"));
        assertFalse(result.get().repaired(), "removing fences is not a repair");
    }

    @Test
    void stripsAFenceWithoutLanguageTagOnASingleLine() {
        assertEquals(1L, object("```{\"a\":1}```").get("a"));
    }

    @Test
    void handlesTheTypicalChattySmallModelReply() {
        String raw = "Sure! Here is the JSON:\n```json\n"
                + "{\"title\": \"Order handling\", \"summary\": \"OrderService places orders and OrderRepository saves them.\"}\n"
                + "```\nLet me know if you need anything else!";

        Optional<LenientJson.Result> result = LenientJson.parseObject(raw);

        assertFalse(result.orElseThrow().repaired());
        assertEquals("Order handling", LenientJson.string(result.get().object(), "title"));
        assertEquals("OrderService places orders and OrderRepository saves them.",
                LenientJson.string(result.get().object(), "summary"));
    }

    @Test
    void skipsLeadingProseAndIgnoresTrailingProseWithoutFences() {
        Optional<LenientJson.Result> result = LenientJson.parseObject(
                "Here you go: {\"a\": 1} -- hope that helps {\"b\": 2}");

        assertEquals(Map.of("a", 1L), result.orElseThrow().object());
        assertFalse(result.get().repaired());
    }

    @Test
    void skipsABraceInLeadingProseThatIsNotJson() {
        assertEquals(Map.of("a", 1L), object("Using the {placeholder} syntax: {\"a\": 1}"));
    }

    @Test
    void fallsBackToTheWholeReplyWhenTheFenceHoldsNoJson() {
        assertEquals(Map.of("a", 1L), object("Run ```mvn test``` first. {\"a\":1}"));
    }

    @Test
    void returnsEmptyWhenThereIsNoObject() {
        assertTrue(LenientJson.parseObject("I am sorry, I cannot help with that.").isEmpty());
        assertTrue(LenientJson.parseObject("[1, 2, 3]").isEmpty());
        assertTrue(LenientJson.parseObject("").isEmpty());
        assertTrue(LenientJson.parseObject(null).isEmpty());
        assertTrue(LenientJson.parseObject("{ this is not json }").isEmpty());
    }

    @Test
    void parseReturnsWhicheverOfObjectOrArrayComesFirst() {
        Object array = LenientJson.parse("Answer: [\"a\", {\"b\": 1}] and {\"c\": 2}").orElseThrow().value();
        Object obj = LenientJson.parse("Answer: {\"c\": [1]} and [2]").orElseThrow().value();

        assertEquals(List.of("a", Map.of("b", 1L)), array);
        assertEquals(Map.of("c", List.of(1L)), obj);
    }

    @Test
    void resultObjectIsEmptyForAnArrayValue() {
        assertEquals(Map.of(), LenientJson.parse("[1]").orElseThrow().object());
    }

    // ------------------------------------------------------------------ syntactic repairs

    @Test
    void repairsTrailingCommasInObjectsAndArrays() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{\"a\": [1, 2,], \"b\": {\"c\": 3,},}");

        assertEquals(Map.of("a", List.of(1L, 2L), "b", Map.of("c", 3L)), result.orElseThrow().object());
        assertTrue(result.get().repaired());
    }

    @Test
    void repairsATrailingCommaInASubQuestionList() {
        Optional<LenientJson.Result> result = LenientJson.parseObject(
                "{\"subQuestions\": [\"Who calls placeOrder?\", \"What does OrderRepository save?\",]}");

        assertTrue(result.orElseThrow().repaired());
        assertEquals(List.of("Who calls placeOrder?", "What does OrderRepository save?"),
                LenientJson.strings(result.get().object(), "subQuestions"));
    }

    @Test
    void repairsDuplicatedCommas() {
        assertEquals(List.of(1L, 2L), object("{\"a\": [1,, 2]}").get("a"));
        assertTrue(repaired("{\"a\": 1,, \"b\": 2}"));
    }

    @Test
    void toleratesAMissingCommaBetweenMembers() {
        assertEquals(Map.of("a", 1L, "b", 2L), object("{\"a\": 1 \"b\": 2}"));
        assertTrue(repaired("{\"a\": 1 \"b\": 2}"));
    }

    @Test
    void acceptsSingleQuotedStrings() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{'name': 'O\\'Brien', \"q\": \"it's\"}");

        assertEquals(Map.of("name", "O'Brien", "q", "it's"), result.orElseThrow().object());
        assertTrue(result.get().repaired());
    }

    @Test
    void acceptsUnquotedKeys() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{title: \"x\", sub_title-2$: 1}");

        assertEquals(Map.of("title", "x", "sub_title-2$", 1L), result.orElseThrow().object());
        assertTrue(result.get().repaired());
    }

    @Test
    void dropsAKeyWhoseValueIsMissingAfterTheColon() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{\"a\": , \"b\": 2, \"c\": }");

        assertEquals(Map.of("b", 2L), result.orElseThrow().object());
        assertTrue(result.get().repaired());
    }

    // ------------------------------------------------------------------ truncation

    @Test
    void repairsAnUnterminatedStringAtTheTokenLimit() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{\"title\": \"Order hand");

        assertEquals(Map.of("title", "Order hand"), result.orElseThrow().object());
        assertTrue(result.get().repaired());
    }

    @Test
    void closesOpenArraysAndObjectsInStackOrder() {
        assertEquals(Map.of("a", List.of(Map.of("b", List.of(1L, 2L)))), object("{\"a\": [{\"b\": [1, 2"));
    }

    @Test
    void dropsADanglingKeyWithoutValue() {
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\""));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\":"));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\": "));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"bo"));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, bo"));
    }

    @Test
    void dropsAnIncompleteLiteral() {
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\": tru"));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\": fals"));
        assertEquals(List.of(1L), object("{\"a\": [1, nul").get("a"));
    }

    @Test
    void dropsAnIncompleteNumber() {
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\": 1."));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\": -"));
        assertEquals(Map.of("a", 1L), object("{\"a\": 1, \"b\": 2e"));
    }

    @Test
    void dropsAnEscapeCutOffByTheTokenLimit() {
        assertEquals(Map.of("a", "x"), object("{\"a\": \"x\\"));
        assertEquals(Map.of("a", "x"), object("{\"a\": \"x\\u00"));
    }

    @Test
    void trailingCommaAtTheTokenLimitIsDropped() {
        assertEquals(Map.of("a", List.of(1L)), object("{\"a\": [1,"));
    }

    /**
     * The token limit hit in the middle of the second entity's name: the unterminated
     * string is closed, so the reply yields two entities — the complete first one and a
     * second one whose name is the cut-off text {@code "Order"} (with no type).
     * Callers that need complete records should validate required fields.
     */
    @Test
    void repairsATruncatedExtractionReplyKeepingTheCutOffEntity() {
        Optional<LenientJson.Result> result = LenientJson.parseObject(
                "{\"entities\":[{\"name\":\"OrderService\",\"type\":\"Class\"},{\"name\":\"Order");

        assertTrue(result.orElseThrow().repaired());
        List<Map<String, Object>> entities = LenientJson.objects(result.get().object(), "entities");
        assertEquals(2, entities.size());
        assertEquals(Map.of("name", "OrderService", "type", "Class"), entities.get(0));
        assertEquals(Map.of("name", "Order"), entities.get(1));
    }

    @Test
    void anOpeningBraceAloneYieldsAnEmptyRepairedObject() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("Here it is: {");

        assertEquals(Map.of(), result.orElseThrow().object());
        assertTrue(result.get().repaired());
    }

    // ------------------------------------------------------------------ strings

    @Test
    void keepsBracesInsideStrings() {
        Map<String, Object> o = object("{\"code\": \"if (x) { return \\\"}\\\"; }\", \"next\": \"[\"}");

        assertEquals("if (x) { return \"}\"; }", o.get("code"));
        assertEquals("[", o.get("next"));
    }

    @Test
    void decodesStandardEscapes() {
        Map<String, Object> o = object("{\"s\": \"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9\"}");

        assertEquals("q\" b\\ s/ \b\f\n\r\t \u00e9", o.get("s"));
    }

    @Test
    void decodesSurrogatePairs() {
        assertEquals("\uD83D\uDE00", object("{\"s\": \"\\uD83D\\uDE00\"}").get("s"));
    }

    @Test
    void acceptsRawControlCharactersInsideStrings() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{\"s\": \"line one\nline two\ttab\"}");

        assertEquals("line one\nline two\ttab", result.orElseThrow().object().get("s"));
    }

    @Test
    void keepsAnInvalidUnicodeEscapeLiterally() {
        Optional<LenientJson.Result> result = LenientJson.parseObject("{\"s\": \"\\uZZ\"}");

        assertEquals("uZZ", result.orElseThrow().object().get("s"));
        assertTrue(result.get().repaired());
    }

    // ------------------------------------------------------------------ limits and robustness

    @Test
    void returnsEmptyInsteadOfOverflowingTheStackOnDeepNesting() {
        String deep = "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000);
        String deepArrays = "[".repeat(100_000);

        assertTrue(LenientJson.parseObject(deep).isEmpty());
        assertTrue(LenientJson.parse(deepArrays).isEmpty());
    }

    @Test
    void acceptsNestingUpToTheLimit() {
        int levels = LenientJson.MAX_DEPTH;
        String nested = "[".repeat(levels - 1) + "]".repeat(levels - 1);

        assertTrue(LenientJson.parseObject("{\"a\":" + nested + "}").isPresent());
    }

    @Test
    void neverThrowsOnRandomInput() {
        Random random = new Random(42);
        String alphabet = "{}[]\"':,\\ \nabtrufelsn0123456789.-eE+u`/";
        for (int i = 0; i < 20_000; i++) {
            int length = random.nextInt(60);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < length; j++) {
                sb.append(random.nextInt(10) == 0
                        ? (char) random.nextInt(0x10000)
                        : alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String raw = sb.toString();
            Optional<LenientJson.Result> object = LenientJson.parseObject(raw);
            object.ifPresent(r -> assertInstanceOf(Map.class, r.value(), raw));
            LenientJson.parse(raw);
            LenientJson.extractObjectText(raw);
        }
    }

    @Test
    void everyTruncationOfAValidObjectYieldsAnObjectThatReserializesStrictly() {
        String document = "{\"entities\": [{\"name\": \"Order\\\"Service\", \"type\": \"Class\", \"score\": -12.5e-1,"
                + " \"ok\": true, \"none\": null, \"tags\": [\"a\", \"\\u00e9\\uD83D\\uDE00\"]},"
                + " {\"name\": \"x\", \"n\": 1234, \"f\": false}], \"summary\": \"{not} [a] brace\"}";
        for (int cut = 0; cut <= document.length(); cut++) {
            String prefix = document.substring(0, cut);
            Optional<LenientJson.Result> result = LenientJson.parseObject(prefix);
            if (cut == 0) {
                assertTrue(result.isEmpty());
                continue;
            }
            assertTrue(result.isPresent(), "no object for prefix: " + prefix);
            assertInstanceOf(Map.class, result.get().value());
            assertEquals(cut < document.length(), result.get().repaired(), prefix);

            String strict = LenientJson.extractObjectText(prefix).orElseThrow();
            LenientJson.Result reparsed = LenientJson.parseObject(strict).orElseThrow();
            assertFalse(reparsed.repaired(), strict);
            assertEquals(result.get().value(), reparsed.value(), strict);
        }
    }

    // ------------------------------------------------------------------ serialization

    @Test
    void extractObjectTextReturnsCanonicalStrictJson() {
        Optional<String> text = LenientJson.extractObjectText(
                "Sure:\n```json\n{title: 'A \"quoted\"\nline', 'n': [1, 2.5, true, null,],}\n```");

        assertEquals(Optional.of("{\"title\":\"A \\\"quoted\\\"\\nline\",\"n\":[1,2.5,true,null]}"), text);
    }

    @Test
    void toJsonEscapesControlCharactersAndLoneSurrogates() {
        assertEquals("\"\\u0001\\t\\ud83d x é\"",
                LenientJson.toJson("\u0001\t\uD83D x \u00e9"));
        assertEquals("\"\uD83D\uDE00\"", LenientJson.toJson("\uD83D\uDE00"));
    }

    @Test
    void toJsonWritesNullsNumbersAndNestedContainers() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", null);
        value.put("b", LenientJson.NULL);
        value.put("c", List.of(1, 2L, 0.5, Double.NaN));
        value.put("d", Map.of("e", false));

        assertEquals("{\"a\":null,\"b\":null,\"c\":[1,2,0.5,null],\"d\":{\"e\":false}}", LenientJson.toJson(value));
    }

    // ------------------------------------------------------------------ accessors

    @Test
    void stringReadsTrimmedTextAndScalarsAsText() {
        Map<String, Object> o = object("{\"s\": \"  x  \", \"n\": 3, \"d\": 2.5, \"b\": true, \"z\": null, \"a\": [1]}");

        assertEquals("x", LenientJson.string(o, "s"));
        assertEquals("3", LenientJson.string(o, "n"));
        assertEquals("2.5", LenientJson.string(o, "d"));
        assertEquals("true", LenientJson.string(o, "b"));
        assertEquals("", LenientJson.string(o, "z"));
        assertEquals("", LenientJson.string(o, "a"));
        assertEquals("", LenientJson.string(o, "missing"));
        assertEquals("", LenientJson.string(null, "s"));
    }

    @Test
    void stringsReadsArraysAndPromotesASingleString() {
        Map<String, Object> o = object("{\"a\": [\" x \", 1, true, [\"nested\"], {\"o\": 1}, null, \"\"], \"s\": \"one\"}");

        assertEquals(List.of("x", "1", "true"), LenientJson.strings(o, "a"));
        assertEquals(List.of("one"), LenientJson.strings(o, "s"));
        assertEquals(List.of(), LenientJson.strings(o, "missing"));
    }

    @Test
    void objectsReadsTheObjectElementsOfAnArray() {
        Map<String, Object> o = object("{\"a\": [{\"x\": 1}, \"skip\", 2, {\"y\": 2}], \"single\": {\"z\": 3}, \"s\": \"no\"}");

        assertEquals(List.of(Map.of("x", 1L), Map.of("y", 2L)), LenientJson.objects(o, "a"));
        assertEquals(List.of(Map.of("z", 3L)), LenientJson.objects(o, "single"));
        assertEquals(List.of(), LenientJson.objects(o, "s"));
        assertEquals(List.of(), LenientJson.objects(o, "missing"));
    }

    @Test
    void numberReadsNumbersAndNumericStrings() {
        Map<String, Object> o = object("{\"i\": 3, \"d\": 0.75, \"s\": \" 0.5 \", \"bad\": \"high\", \"b\": true}");

        assertEquals(OptionalDouble.of(3), LenientJson.number(o, "i"));
        assertEquals(OptionalDouble.of(0.75), LenientJson.number(o, "d"));
        assertEquals(OptionalDouble.of(0.5), LenientJson.number(o, "s"));
        assertEquals(OptionalDouble.empty(), LenientJson.number(o, "bad"));
        assertEquals(OptionalDouble.empty(), LenientJson.number(o, "b"));
        assertEquals(OptionalDouble.empty(), LenientJson.number(o, "missing"));
    }
}
