package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonStats;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.llm.PromptedLlmPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.EntityTypes;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiLlmPortTest {

    private static final TextUnit UNIT = new TextUnit("u0", "c1", "doc.txt", 0, "Ada Lovelace met Charles Babbage.");
    private static final List<Entity> MEMBERS = List.of(
            new Entity("Sherlock Holmes", "Person", "A consulting detective.", List.of()),
            new Entity("Dr. Watson", "Person", "Friend and chronicler of Holmes.", List.of()));
    private static final List<Relationship> RELATIONSHIPS = List.of(
            new Relationship("Sherlock Holmes", "Person", "works_with", "Dr. Watson", "Person",
                    "They solve cases together.", List.of(), 3));
    private static final List<ContextItem> ANSWER_CONTEXT = List.of(
            new ContextItem(1, RetrievalStep.Kind.ENTITY, "Irene Adler (Person): An opera singer.", null),
            new ContextItem(2, RetrievalStep.Kind.TEXT_UNIT, "Irene Adler sang at the opera.", "tu-a"));
    private static final ComparisonFacts FACTS = new ComparisonFacts("LOCAL", new ComparisonStats(7, 2, 120),
            new ComparisonStats(5, 1, 40), 5, 2);

    private static final PromptedLlmPort.Options DEFAULTS = PromptedLlmPort.Options.defaults();

    private static OpenAiLlmPort port(ChatModel model) {
        return new OpenAiLlmPort(model, PromptedLlmPort.Options.defaults().withCorrectiveRetry(false));
    }

    @Test
    void rejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmPort(" "));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmPort(null));
    }

    @Test
    void extractsAndSynthesizesWhateverTheOptionsSayButKeepsDeterministicSubQuestions() {
        OpenAiLlmPort port = new OpenAiLlmPort(new ScriptedChatModel(), PromptedLlmPort.Options.defaults());

        assertTrue(port.extractsEntities());
        assertTrue(port.synthesizesAnswers());
        assertTrue(port.summarizesCommunities());
        assertFalse(port.derivesSubQuestions());
        List<Community> communities = List.of(new Community("c-1", "Detectives", "Holmes and Watson."));
        assertEquals(LlmPort.none().deriveDriftSubQuestions("Who?", communities),
                port.deriveDriftSubQuestions("Who?", communities));
    }

    @Test
    void sendsTheCorePromptAsOneUserMessageWithThePurposeTokenLimit() {
        ScriptedChatModel model = new ScriptedChatModel("""
                ```json
                { "entities": [ { "name": "Ada Lovelace", "type": "Person", "description": "A mathematician." } ],
                  "relationships": [] }
                ```""");

        GraphExtraction extraction = port(model).extract(UNIT, EntityTypes.ALL);

        assertEquals("Ada Lovelace", extraction.entities().getFirst().name());
        assertEquals("A mathematician.", extraction.entities().getFirst().description());
        assertEquals(1, model.requests.size());
        assertEquals(DEFAULTS.extractionTokens(), model.maxOutputTokens.getFirst());
        String prompt = model.userText(0, 0);
        assertTrue(prompt.toLowerCase().contains("json"), "JSON mode requires the word json in the prompt");
        assertTrue(prompt.contains("most complete name"));
        assertTrue(prompt.contains(UNIT.text()));
        for (String type : EntityTypes.ALL) {
            assertTrue(prompt.contains(type), "prompt should list " + type);
        }
    }

    @Test
    void gleaningSendsTheWholeConversation() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"entities\":[{\"name\":\"Ada Lovelace\",\"type\":\"Person\"}],\"relationships\":[]}",
                "{\"entities\":[{\"name\":\"Charles Babbage\",\"type\":\"Person\"}],\"relationships\":[]}",
                "{\"entities\":[],\"relationships\":[]}");

        GraphExtraction extraction = new OpenAiLlmPort(model, PromptedLlmPort.Options.defaults().withGleanings(2))
                .extract(UNIT, EntityTypes.ALL);

        assertEquals(List.of("Ada Lovelace", "Charles Babbage"),
                extraction.entities().stream().map(Entity::name).toList());
        assertEquals(3, model.requests.size());
        List<ChatMessage> second = model.requests.get(1);
        assertEquals(3, second.size());
        assertInstanceOf(AiMessage.class, second.get(1));
        assertTrue(model.userText(1, 2).toLowerCase().contains("json"));
    }

    @Test
    void aReplyCutOffByTheTokenLimitFailsVisibly() {
        ScriptedChatModel model = new ScriptedChatModel(FinishReason.LENGTH, "{\"entities\":[]");

        OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> port(model).extract(UNIT, EntityTypes.ALL));

        assertTrue(failure.getMessage().contains("OpenAI extraction response hit the output-token limit (4096)"));
    }

    @Test
    void aFailingModelIsWrappedAndNotRetried() {
        List<Integer> calls = new ArrayList<>();
        ChatModel down = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.add(1);
                throw new IllegalStateException("network");
            }
        };
        OpenAiLlmPort port = new OpenAiLlmPort(down, PromptedLlmPort.Options.defaults());

        OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> port.summarizeCommunity(MEMBERS, RELATIONSHIPS));

        assertEquals("OpenAI community summary call failed", failure.getMessage());
        assertEquals(1, calls.size());
        assertThrows(OpenAiLlmPort.LlmCallFailedException.class, () -> port.synthesizeAnswer("q", ANSWER_CONTEXT));
        assertThrows(OpenAiLlmPort.LlmCallFailedException.class, () -> port.compareAnswers("q", "a", "b", FACTS));
    }

    @Test
    void anUnusableReplyIsAnLlmCallFailedException() {
        for (String reply : List.of("Not JSON.", "", "{\"title\":\"x\",\"summary\":\" \"}")) {
            OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                    () -> port(new ScriptedChatModel(reply)).summarizeCommunity(MEMBERS, RELATIONSHIPS));
            assertTrue(failure.getMessage().startsWith("OpenAI "), failure.getMessage());
        }
    }

    @Test
    void summarizesACommunityFromTheCorePrompt() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"title\":\"One two three four five six seven\",\"summary\":\"Holmes and Watson solve cases.\"}");

        CommunitySummary summary = port(model).summarizeCommunity(MEMBERS, RELATIONSHIPS);

        assertEquals(new CommunitySummary("One two three four five six", "Holmes and Watson solve cases."), summary);
        assertEquals(DEFAULTS.summaryTokens(), model.maxOutputTokens.getFirst());
        assertTrue(model.userText(0, 0).contains("1. Sherlock Holmes -[works_with]-> Dr. Watson: They solve cases "
                + "together."));
    }

    @Test
    void synthesizesAnAnswerAndReportsNotInContext() {
        ScriptedChatModel model = new ScriptedChatModel("{\"answer\":\"She sang [2].\",\"notInContext\":false}");

        assertEquals(new SynthesizedAnswer(false, "She sang [2]."), port(model).synthesizeAnswer("Who?",
                ANSWER_CONTEXT));
        assertEquals(DEFAULTS.answerTokens(), model.maxOutputTokens.getFirst());
        assertTrue(model.userText(0, 0).contains("[2] Source passage: Irene Adler sang at the opera."));
        for (String body : List.of("{\"answer\":\"NOT_IN_CONTEXT\",\"notInContext\":false}",
                "{\"answer\":\"\",\"notInContext\":true}")) {
            assertEquals(new SynthesizedAnswer(true, ""),
                    port(new ScriptedChatModel(body)).synthesizeAnswer("q", ANSWER_CONTEXT));
        }
    }

    @Test
    void compareAnswersKeepsAtMostTwoSentences() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"verdict\":\"GraphRAG cites two documents. Vector Search stays in one. Extra.\"}");

        ComparisonVerdict verdict = port(model).compareAnswers("q", "a", "b", FACTS);

        assertEquals(new ComparisonVerdict("GraphRAG cites two documents. Vector Search stays in one.",
                ComparisonVerdict.Source.LLM), verdict);
        assertEquals(DEFAULTS.verdictTokens(), model.maxOutputTokens.getFirst());
        assertTrue(model.userText(0, 0).contains("- GraphRAG: 7 context items from 2 distinct documents, 120 ms"));
    }

    @Test
    void firstSentencesCutsAfterTheGivenCount() {
        assertEquals("One. Two!", OpenAiLlmPort.firstSentences("One. Two! Three?", 2));
        assertEquals("Only one sentence", OpenAiLlmPort.firstSentences("Only one sentence", 2));
        assertEquals("Version 2.5 is out. Yes.", OpenAiLlmPort.firstSentences("Version 2.5 is out. Yes. No.", 2));
    }

    @Test
    void firstSentencesDoesNotCutAtAbbreviationsOrInitials() {
        assertEquals("Mr. Holmes met Dr. Watson at St. Bart's. Then they left.",
                OpenAiLlmPort.firstSentences("Mr. Holmes met Dr. Watson at St. Bart's. Then they left. Extra.", 2));
        assertEquals("GraphRAG read more, e.g. the letters. Vector Search did not, i.e. it stayed local.",
                OpenAiLlmPort.firstSentences(
                        "GraphRAG read more, e.g. the letters. Vector Search did not, i.e. it stayed local. More.", 2));
        assertEquals("J. H. Watson wrote it. It is short.",
                OpenAiLlmPort.firstSentences("J. H. Watson wrote it. It is short. Third.", 2));
        assertEquals("Mrs. Hudson called. Done.", OpenAiLlmPort.firstSentences("Mrs. Hudson called. Done. Cut.", 2));
    }

    /** Replies in order with one finish reason and records every request. */
    static final class ScriptedChatModel implements ChatModel {
        private final FinishReason finishReason;
        private final Deque<String> replies;
        final List<List<ChatMessage>> requests = new ArrayList<>();
        final List<Integer> maxOutputTokens = new ArrayList<>();

        ScriptedChatModel(String... replies) {
            this(FinishReason.STOP, replies);
        }

        ScriptedChatModel(FinishReason finishReason, String... replies) {
            this.finishReason = finishReason;
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        String userText(int request, int message) {
            return ((UserMessage) requests.get(request).get(message)).singleText();
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request.messages());
            maxOutputTokens.add(request.maxOutputTokens());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(replies.isEmpty() ? "" : replies.removeFirst()))
                    .finishReason(finishReason)
                    .build();
        }
    }
}
