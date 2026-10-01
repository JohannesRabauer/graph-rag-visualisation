package com.graphraglens.adapter.langchain4j;

import io.graphrag.core.domain.CommunitySummary;
import io.graphrag.core.domain.ContextItem;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.domain.SynthesizedAnswer;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.usecase.EntityTypes;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiLlmPortTest {

    @Test
    void rejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmPort(" "));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmPort(null));
    }

    @Test
    void parsesWellFormedJsonExtractionResponse() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        String response = """
                {
                  "entities": [
                    { "name": "Sherlock Holmes", "type": "Person", "description": "A detective." },
                    { "name": "Dr. Watson", "type": "Person" }
                  ],
                  "relationships": [
                    { "source": "Sherlock Holmes", "sourceType": "Person", "type": "met", "target": "Dr. Watson", "targetType": "Person", "description": "Holmes met Watson." }
                  ]
                }
                """;

        GraphExtraction extraction = port.parseExtraction(response);

        assertEquals(2, extraction.entities().size());
        assertEquals(1, extraction.relationships().size());
        assertEquals("A detective.", extraction.entities().getFirst().description());
        assertEquals("", extraction.entities().get(1).description());
        assertEquals("Holmes met Watson.", extraction.relationships().getFirst().description());
        assertTrue(extraction.relationships().getFirst().type().equals("met"));
    }

    @Test
    void stripsMarkdownCodeFencesBeforeParsing() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        String response = """
                ```json
                { "entities": [ { "name": "Irene Adler", "type": "Person" } ], "relationships": [] }
                ```
                """;

        GraphExtraction extraction = port.parseExtraction(response);

        assertEquals(1, extraction.entities().size());
        assertEquals("Irene Adler", extraction.entities().getFirst().name());
    }

    @Test
    void invalidJsonSurfacesAsLlmCallFailedException() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        assertThrows(OpenAiLlmPort.LlmCallFailedException.class, () -> port.parseExtraction("not json at all"));
    }

    @Test
    void blankResponseYieldsEmptyExtraction() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        GraphExtraction extraction = port.parseExtraction("   ");

        assertTrue(extraction.entities().isEmpty());
        assertTrue(extraction.relationships().isEmpty());
    }

    @Test
    void extractionPromptListsEveryEntityTypeAndContainsTheUnitText() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");
        TextUnit unit = new TextUnit("c1::doc-0::tu-3", "c1", "history-of-java.txt", 3,
                "James Gosling started the Green Project at Sun Microsystems in 1991.");

        String prompt = port.extractionPrompt(unit, EntityTypes.ALL);

        for (String type : EntityTypes.ALL) {
            assertTrue(prompt.contains(type), "prompt should list " + type);
        }
        assertTrue(prompt.contains(unit.text()));
        assertTrue(prompt.toLowerCase().contains("json"), "JSON mode requires the word json in the prompt");
        assertTrue(prompt.contains("description"));
    }

    @Test
    void extractThrowsWhenFinishReasonIsLength() {
        ChatModel json = new FakeChatModel(FinishReason.LENGTH, "{\"entities\":[],\"relationships\":[]}");
        OpenAiLlmPort port = new OpenAiLlmPort(json, new FakeChatModel(FinishReason.STOP, ""));

        OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> port.extract(new TextUnit("u0", "c1", "doc.txt", 0, "Ada Lovelace."), EntityTypes.ALL));

        assertTrue(failure.getMessage().contains("output-token limit"));
    }

    @Test
    void extractParsesStopResponseFromChatRequest() {
        String response = """
                { "entities": [ { "name": "Ada Lovelace", "type": "Person", "description": "A mathematician." } ],
                  "relationships": [] }
                """;
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, response),
                new FakeChatModel(FinishReason.STOP, ""));

        GraphExtraction extraction = port.extract(new TextUnit("u0", "c1", "doc.txt", 0, "Ada Lovelace."),
                EntityTypes.ALL);

        assertEquals("Ada Lovelace", extraction.entities().getFirst().name());
        assertEquals("A mathematician.", extraction.entities().getFirst().description());
    }

    private static final List<Entity> MEMBERS = List.of(
            new Entity("Sherlock Holmes", "Person", "A consulting detective. " + "x".repeat(400), List.of()),
            new Entity("Dr. Watson", "Person", "Friend and chronicler of Holmes.", List.of()));
    private static final List<Relationship> RELATIONSHIPS = List.of(
            new Relationship("Sherlock Holmes", "Person", "works_with", "Dr. Watson", "Person",
                    "They solve cases together.", List.of(), 3));

    @Test
    void communitySummaryPromptMentionsJsonNumbersMembersAndRelationshipsAndTruncatesDescriptions() {
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, "{}"),
                new FakeChatModel(FinishReason.STOP, ""));

        String prompt = port.communitySummaryPrompt(MEMBERS, RELATIONSHIPS);

        assertTrue(prompt.toLowerCase().contains("json"));
        assertTrue(prompt.contains("1. Sherlock Holmes (Person): A consulting detective."));
        assertTrue(prompt.contains("2. Dr. Watson (Person): Friend and chronicler of Holmes."));
        assertTrue(prompt.contains("1. Sherlock Holmes -[works_with]-> Dr. Watson: They solve cases together."));
        String longDescription = MEMBERS.getFirst().description();
        assertTrue(prompt.contains(longDescription.substring(0, OpenAiLlmPort.MAX_PROMPT_DESCRIPTION_CHARS)));
        assertFalse(prompt.contains(longDescription.substring(0, OpenAiLlmPort.MAX_PROMPT_DESCRIPTION_CHARS + 1)));
    }

    @Test
    void summarizeCommunityParsesTitleAndSummaryFromJsonModel() {
        FakeChatModel json = new FakeChatModel(FinishReason.STOP,
                "{\"title\":\"Baker Street Detectives\",\"summary\":\"Holmes and Watson solve cases.\"}",
                OpenAiLlmPort.MAX_SUMMARY_OUTPUT_TOKENS);
        OpenAiLlmPort port = new OpenAiLlmPort(json, new FakeChatModel(FinishReason.STOP, ""));

        CommunitySummary summary = port.summarizeCommunity(MEMBERS, RELATIONSHIPS);

        assertEquals(new CommunitySummary("Baker Street Detectives", "Holmes and Watson solve cases."), summary);
        assertEquals(1, json.requests.size());
        assertTrue(json.requests.getFirst().contains("Dr. Watson"));
    }

    @Test
    void summarizeCommunityTrimsLongTitlesToSixWords() {
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP,
                "```json\n{\"title\":\"One two three four five six seven eight nine\",\"summary\":\"S.\"}\n```",
                OpenAiLlmPort.MAX_SUMMARY_OUTPUT_TOKENS), new FakeChatModel(FinishReason.STOP, ""));

        assertEquals("One two three four five six", port.summarizeCommunity(MEMBERS, RELATIONSHIPS).title());
    }

    @Test
    void summarizeCommunityFallsBackToDeterministicSummaryWhenBlankAndEmptiesBlankTitle() {
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP,
                "{\"title\":\"  \",\"summary\":\"\"}", OpenAiLlmPort.MAX_SUMMARY_OUTPUT_TOKENS),
                new FakeChatModel(FinishReason.STOP, ""));

        CommunitySummary summary = port.summarizeCommunity(MEMBERS, RELATIONSHIPS);

        assertEquals("", summary.title());
        assertEquals("This community centers on Sherlock Holmes, Dr. Watson.", summary.summary());
    }

    @Test
    void summarizeCommunityThrowsOnInvalidJson() {
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP,
                "Here is a summary, not JSON.", OpenAiLlmPort.MAX_SUMMARY_OUTPUT_TOKENS),
                new FakeChatModel(FinishReason.STOP, ""));

        OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> port.summarizeCommunity(MEMBERS, RELATIONSHIPS));

        assertTrue(failure.getMessage().contains("not valid JSON"));
    }

    @Test
    void summarizeCommunityWrapsModelFailures() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        OpenAiLlmPort port = new OpenAiLlmPort(failing, new FakeChatModel(FinishReason.STOP, ""));

        assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> port.summarizeCommunity(MEMBERS, RELATIONSHIPS));
    }

    private static final List<ContextItem> ANSWER_CONTEXT = List.of(
            new ContextItem(1, RetrievalStep.Kind.ENTITY, "Irene Adler (Person): An opera singer.", null),
            new ContextItem(2, RetrievalStep.Kind.RELATIONSHIP, "Sherlock Holmes -[admired]-> Irene Adler", null),
            new ContextItem(3, RetrievalStep.Kind.TEXT_UNIT, "P".repeat(2000), "tu-a"));

    @Test
    void synthesizesAnswers() {
        assertTrue(new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, "{}"),
                new FakeChatModel(FinishReason.STOP, "")).synthesizesAnswers());
    }

    @Test
    void answerPromptNumbersEveryItemDemandsCitationsAndTruncatesPassages() {
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, "{}"),
                new FakeChatModel(FinishReason.STOP, ""));

        String prompt = port.answerPrompt("Who is Irene Adler?", ANSWER_CONTEXT);

        assertTrue(prompt.toLowerCase().contains("json"));
        assertTrue(prompt.contains("[1] Entity: Irene Adler (Person): An opera singer."));
        assertTrue(prompt.contains("[2] Relationship: Sherlock Holmes -[admired]-> Irene Adler"));
        assertTrue(prompt.contains("[3] Source passage: "));
        assertTrue(prompt.contains("[n]"));
        assertTrue(prompt.contains("only with the numbers of \"Source passage\" items"));
        assertTrue(prompt.contains("Entity, Relationship and Community summary items are background facts"));
        assertTrue(prompt.contains("Only \"Source passage\" items may be cited; never put [n] on an Entity, "
                + "Relationship or Community summary item."));
        assertFalse(prompt.contains("Prefer citing"));
        assertTrue(prompt.contains(OpenAiLlmPort.NOT_IN_CONTEXT));
        assertTrue(prompt.contains("Question: Who is Irene Adler?"));
        assertTrue(prompt.contains("P".repeat(OpenAiLlmPort.MAX_PROMPT_CONTEXT_ITEM_CHARS)));
        assertFalse(prompt.contains("P".repeat(OpenAiLlmPort.MAX_PROMPT_CONTEXT_ITEM_CHARS + 1)));
    }

    @Test
    void answerPromptLabelsCommunitySummariesAsUncitableBackground() {
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, "{}"),
                new FakeChatModel(FinishReason.STOP, ""));

        String prompt = port.answerPrompt("What is the corpus about?", List.of(
                new ContextItem(1, RetrievalStep.Kind.COMMUNITY, "Rivals: Holmes and Adler clash.", null),
                new ContextItem(2, RetrievalStep.Kind.TEXT_UNIT, "Holmes admired her.", "tu-b")));

        assertTrue(prompt.contains("[1] Community summary: Rivals: Holmes and Adler clash."));
        assertTrue(prompt.contains("[2] Source passage: Holmes admired her."));
        assertTrue(prompt.contains("Entity, Relationship and Community summary items are background facts: "
                + "use them, but never cite them."));
        assertFalse(prompt.contains("Never put [n] on an Entity or Relationship item."));
        assertTrue(prompt.contains("community summaries from its knowledge graph, and source passages."));
    }

    @Test
    void synthesizeAnswerParsesTheAnswerFromOneJsonCall() {
        FakeChatModel json = new FakeChatModel(FinishReason.STOP,
                "```json\n{\"answer\":\"She is a singer [3].\",\"notInContext\":false}\n```",
                OpenAiLlmPort.MAX_ANSWER_OUTPUT_TOKENS);
        OpenAiLlmPort port = new OpenAiLlmPort(json, new FakeChatModel(FinishReason.STOP, ""));

        SynthesizedAnswer answer = port.synthesizeAnswer("Who is Irene Adler?", ANSWER_CONTEXT);

        assertEquals(new SynthesizedAnswer(false, "She is a singer [3]."), answer);
        assertEquals(1, json.requests.size());
        assertTrue(json.requests.getFirst().contains("[3] Source passage"));
    }

    @Test
    void synthesizeAnswerReportsNotInContext() {
        for (String body : List.of("{\"answer\":\"NOT_IN_CONTEXT\",\"notInContext\":false}",
                "{\"answer\":\" \\\"not_in_context.\\\" \",\"notInContext\":false}",
                "{\"answer\":\"\",\"notInContext\":true}")) {
            OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, body,
                    OpenAiLlmPort.MAX_ANSWER_OUTPUT_TOKENS), new FakeChatModel(FinishReason.STOP, ""));

            assertEquals(new SynthesizedAnswer(true, ""), port.synthesizeAnswer("q", ANSWER_CONTEXT));
        }
    }

    @Test
    void synthesizeAnswerThrowsOnInvalidJsonLengthOrModelFailure() {
        OpenAiLlmPort invalid = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, "Not JSON.",
                OpenAiLlmPort.MAX_ANSWER_OUTPUT_TOKENS), new FakeChatModel(FinishReason.STOP, ""));
        assertTrue(assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> invalid.synthesizeAnswer("q", ANSWER_CONTEXT)).getMessage().contains("not valid JSON"));

        OpenAiLlmPort truncated = new OpenAiLlmPort(new FakeChatModel(FinishReason.LENGTH,
                "{\"answer\":\"cut", OpenAiLlmPort.MAX_ANSWER_OUTPUT_TOKENS), new FakeChatModel(FinishReason.STOP, ""));
        assertTrue(assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> truncated.synthesizeAnswer("q", ANSWER_CONTEXT)).getMessage().contains("output-token limit"));

        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        OpenAiLlmPort down = new OpenAiLlmPort(failing, new FakeChatModel(FinishReason.STOP, ""));
        assertThrows(OpenAiLlmPort.LlmCallFailedException.class, () -> down.synthesizeAnswer("q", ANSWER_CONTEXT));
    }

    private static final class FakeChatModel implements ChatModel {
        private final FinishReason finishReason;
        private final String text;
        private final int expectedMaxOutputTokens;
        private final List<String> requests = new ArrayList<>();

        private FakeChatModel(FinishReason finishReason, String text) {
            this(finishReason, text, OpenAiLlmPort.MAX_EXTRACTION_OUTPUT_TOKENS);
        }

        private FakeChatModel(FinishReason finishReason, String text, int expectedMaxOutputTokens) {
            this.finishReason = finishReason;
            this.text = text;
            this.expectedMaxOutputTokens = expectedMaxOutputTokens;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            assertEquals(Integer.valueOf(expectedMaxOutputTokens), request.maxOutputTokens());
            assertEquals(1, request.messages().size());
            requests.add(((dev.langchain4j.data.message.UserMessage) request.messages().getFirst()).singleText());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .finishReason(finishReason)
                    .build();
        }
    }
}
