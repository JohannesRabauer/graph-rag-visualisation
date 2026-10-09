package dev.rabauer.graphrag.core.llm;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityPoint;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptedLlmPortTest {

    private static final List<Entity> MEMBERS = List.of(new Entity("OrderService", "Class", "Places orders.", List.of()),
            new Entity("OrderRepository", "Interface"));
    private static final List<Relationship> INTERNAL = List.of(
            new Relationship("OrderService", "Class", "CALLS", "OrderRepository", "Interface"));

    /** Answers with scripted replies, in order, and records every request. */
    static class ScriptedPort extends PromptedLlmPort {
        final Deque<String> replies;
        final List<CompletionRequest> requests = new ArrayList<>();

        ScriptedPort(Options options, String... replies) {
            super(options);
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        ScriptedPort(String... replies) {
            this(Options.defaults(), replies);
        }

        @Override
        protected String complete(CompletionRequest request) {
            requests.add(request);
            return replies.isEmpty() ? "" : replies.removeFirst();
        }
    }

    @Test
    void parsesASummaryWrappedInProseAndMarkdown() {
        ScriptedPort port = new ScriptedPort("""
                Sure! Here is the summary:
                ```json
                {"title": "Order placement and storage flow overview", "summary": "OrderService stores orders."}
                ```
                Let me know if you need more.""");

        CommunitySummary summary = port.summarizeCommunity(MEMBERS, INTERNAL);

        assertEquals("Order placement and storage flow overview", summary.title());
        assertEquals("OrderService stores orders.", summary.summary());
        assertEquals(1, port.requests.size());
        PromptedLlmPort.CompletionRequest request = port.requests.getFirst();
        assertEquals(PromptedLlmPort.Purpose.COMMUNITY_SUMMARY, request.purpose());
        assertEquals(PromptedLlmPort.Schemas.COMMUNITY_SUMMARY, request.jsonSchema());
        assertTrue(request.lastUserText().contains("OrderService (Class): Places orders."));
        assertTrue(request.lastUserText().contains("OrderService -[CALLS]-> OrderRepository"));
    }

    @Test
    void repairsASummaryCutOffByTheTokenLimit() {
        ScriptedPort port = new ScriptedPort("{\"title\": \"Orders\", \"summary\": \"OrderService places orders and");

        CommunitySummary summary = port.summarizeCommunity(MEMBERS, INTERNAL);

        assertEquals("OrderService places orders and", summary.summary());
        assertEquals(1, port.requests.size());
    }

    @Test
    void asksOnceMoreWithTheBadReplyAndACorrection() {
        ScriptedPort port = new ScriptedPort("It is about orders.", "{\"title\":\"Orders\",\"summary\":\"About orders.\"}");

        CommunitySummary summary = port.summarizeCommunity(MEMBERS, INTERNAL);

        assertEquals("About orders.", summary.summary());
        assertEquals(2, port.requests.size());
        List<PromptedLlmPort.CompletionRequest.Message> retry = port.requests.get(1).messages();
        assertEquals(List.of(PromptedLlmPort.Role.USER, PromptedLlmPort.Role.ASSISTANT, PromptedLlmPort.Role.USER),
                retry.stream().map(PromptedLlmPort.CompletionRequest.Message::role).toList());
        assertEquals("It is about orders.", retry.get(1).text());
        assertTrue(retry.get(2).text().contains("did not contain a JSON object"));
        assertTrue(retry.get(2).text().contains(PromptedLlmPort.Schemas.COMMUNITY_SUMMARY));
    }

    @Test
    void aMissingRequiredFieldAlsoTriggersTheCorrection() {
        ScriptedPort port = new ScriptedPort("{\"title\":\"Orders\"}", "{\"title\":\"Orders\",\"summary\":\"Ok.\"}");

        assertEquals("Ok.", port.summarizeCommunity(MEMBERS, INTERNAL).summary());
        assertTrue(port.requests.get(1).lastUserText().contains("missing required fields"));
    }

    @Test
    void failsTheItemVisiblyWhenTheRetryIsUnusableToo() {
        ScriptedPort port = new ScriptedPort("no json", "still no json");

        LlmReplyException failure = assertThrows(LlmReplyException.class,
                () -> port.summarizeCommunity(MEMBERS, INTERNAL));

        assertEquals(PromptedLlmPort.Purpose.COMMUNITY_SUMMARY, failure.purpose());
        assertEquals(2, failure.attempts());
        assertEquals("still no json", failure.lastReply());
        assertTrue(failure.getMessage().contains("still no json"));
    }

    @Test
    void withoutTheCorrectiveRetryOneUnusableReplyFails() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withCorrectiveRetry(false), "no json",
                "{\"title\":\"x\",\"summary\":\"y\"}");

        assertEquals(1, assertThrows(LlmReplyException.class,
                () -> port.summarizeCommunity(MEMBERS, INTERNAL)).attempts());
        assertEquals(1, port.requests.size());
    }

    @Test
    void aFailingClientPropagatesUnchangedAndIsNotRetried() {
        IllegalStateException outage = new IllegalStateException("connection refused");
        List<Integer> calls = new ArrayList<>();
        PromptedLlmPort port = new PromptedLlmPort() {
            @Override
            protected String complete(CompletionRequest request) {
                calls.add(1);
                throw outage;
            }
        };

        assertSame(outage, assertThrows(IllegalStateException.class, () -> port.summarizeCommunity(MEMBERS, INTERNAL)));
        assertEquals(1, calls.size());
    }

    @Test
    void padsMissingSubQuestionsAndDropsExtraOnes() {
        List<Community> communities = List.of(new Community("c-1", "Orders", "Order handling."),
                new Community("c-2", "Payments", "Charging."));
        ScriptedPort fewer = new ScriptedPort("{\"subQuestions\": [\"Who calls placeOrder?\",]}");
        ScriptedPort more = new ScriptedPort("{\"subQuestions\": [\"a?\", \"b?\", \"c?\"]}");

        assertEquals(List.of("Who calls placeOrder?", "How does placeOrder work? Community summary: Charging."),
                fewer.deriveDriftSubQuestions("How does placeOrder work?", communities));
        assertEquals(List.of("a?", "b?"), more.deriveDriftSubQuestions("q", communities));
        assertEquals(PromptedLlmPort.Schemas.SUB_QUESTIONS, fewer.requests.getFirst().jsonSchema());
        assertTrue(fewer.requests.getFirst().lastUserText().contains("(2 questions)"));
    }

    @Test
    void extractionIsOffByDefaultAndMakesNoCall() {
        ScriptedPort port = new ScriptedPort();

        assertFalse(port.extractsEntities());
        assertEquals(GraphExtraction.empty(), port.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."),
                List.of("Person")));
        assertTrue(port.requests.isEmpty());
    }

    @Test
    void extractionParsesLenientlyRestrictsTypesAndSkipsIncompleteItems() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true), """
                {"entities":[{"name":"OrderService","type":"class","description":"Places orders."},
                {"name":"","type":"Class"},{"name":"Bob","type":"Wizard"}],
                "relationships":[{"source":"OrderService","sourceType":"Class","type":"CALLS",
                "target":"OrderRepository","targetType":"Interface"},{"source":"x"}""");

        GraphExtraction extraction = port.extract(new TextUnit("t", "c", "OrderService.java", 0, "class OrderService {}"),
                List.of("Class", "Interface", "Other"));

        assertTrue(port.extractsEntities());
        assertEquals(List.of("OrderService", "Bob"), extraction.entities().stream().map(Entity::name).toList());
        assertEquals(List.of("Class", "Other"), extraction.entities().stream().map(Entity::type).toList());
        assertEquals(1, extraction.relationships().size());
        assertEquals("Interface", extraction.relationships().getFirst().targetType());
        assertTrue(port.requests.getFirst().jsonSchema().contains("\"enum\":[\"Class\",\"Interface\",\"Other\"]"));
    }

    @Test
    void synthesisIsOffByDefaultAndParsesAnswersWhenOn() {
        List<ContextItem> context = List.of(new ContextItem(1, RetrievalStep.Kind.TEXT_UNIT, "save(order)", "t-1"));
        ScriptedPort off = new ScriptedPort();
        ScriptedPort on = new ScriptedPort(PromptedLlmPort.Options.defaults().withSynthesis(true),
                "{\"answer\": \"It saves the order [1].\", \"notInContext\": false}",
                "{\"answer\": \"NOT_IN_CONTEXT\", \"notInContext\": true}");

        assertFalse(off.synthesizesAnswers());
        assertNull(off.synthesizeAnswer("q", context));
        assertTrue(on.synthesizesAnswers());
        assertEquals(new SynthesizedAnswer(false, "It saves the order [1]."), on.synthesizeAnswer("q", context));
        assertTrue(on.synthesizeAnswer("q", context).notInContext());
        assertTrue(on.requests.getFirst().lastUserText().contains("[1] Source passage: save(order)"));
    }

    @Test
    void extractionPromptStatesTheNamingAndRelationshipRules() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true),
                "{\"entities\":[],\"relationships\":[]}");

        port.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."), List.of("Person", "Other"));

        String prompt = port.requests.getFirst().lastUserText();
        assertTrue(prompt.contains("Entity types: Person, Other. Use \"Other\" when no other type fits."));
        assertTrue(prompt.contains("most complete name"));
        assertTrue(prompt.contains("must be an entity listed in \"entities\""));
        assertTrue(prompt.contains("\"works_for\""));
        assertTrue(prompt.endsWith("Passage (a.txt, part 1):\nAda met Bob."));
    }

    @Test
    void knownEntityNamesAreListedBeforeThePassageAndKeptForGleaning() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true).withGleanings(1),
                "{\"entities\":[{\"name\":\"Ada Lovelace\",\"type\":\"Person\"}],\"relationships\":[]}",
                "{\"entities\":[],\"relationships\":[]}");
        TextUnit unit = new TextUnit("t", "c", "a.txt", 1, "Ada wrote to Babbage, Charles.");

        port.extract(unit, List.of("Person", "Other"), List.of("Ada Lovelace", "Babbage, Charles"));

        String prompt = port.requests.getFirst().lastUserText();
        assertTrue(prompt.contains("use its name exactly:\nAda Lovelace; Babbage, Charles\n\nPassage (a.txt, part 2):"));
        assertEquals(prompt, port.requests.get(1).messages().getFirst().text());
        assertFalse(new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true),
                "{\"entities\":[],\"relationships\":[]}").extractionPrompt(unit, List.of("Person"), List.of())
                .contains("earlier passages"));
    }

    @Test
    void descriptionSummariesAreOffByDefaultAndOneCallWhenOn() {
        ScriptedPort off = new ScriptedPort();
        ScriptedPort on = new ScriptedPort(PromptedLlmPort.Options.defaults().withDescriptionSummaries(true),
                "{\"description\": \"Ada wrote the first program.\"}");

        assertFalse(off.summarizesDescriptions());
        assertEquals("Ada wrote. Ada met Bob.", off.summarizeDescription("Ada", "Ada wrote. Ada met Bob."));
        assertTrue(off.requests.isEmpty());
        assertTrue(on.summarizesDescriptions());
        assertEquals("Ada wrote the first program.", on.summarizeDescription("Ada", "Ada wrote. Ada met Bob."));
        PromptedLlmPort.CompletionRequest request = on.requests.getFirst();
        assertEquals(PromptedLlmPort.Purpose.DESCRIPTION_SUMMARY, request.purpose());
        assertEquals(PromptedLlmPort.Schemas.DESCRIPTION_SUMMARY, request.jsonSchema());
        assertTrue(request.lastUserText().contains("at most 1000 characters"));
        assertTrue(request.lastUserText().endsWith("Element: Ada\nNotes:\nAda wrote. Ada met Bob."));
    }

    @Test
    void mapsCommunitiesToScoredPointsWhenSynthesisIsOn() {
        List<Community> batch = List.of(new Community("c-1", "Orders", "Order handling."),
                new Community("c-2", "Payments", "Charging."));
        ScriptedPort off = new ScriptedPort();
        ScriptedPort on = new ScriptedPort(PromptedLlmPort.Options.defaults().withSynthesis(true), """
                {"points":[{"community":2,"point":"Payments are charged.","score":140},
                {"community":3,"point":"No such group.","score":50},{"community":1,"point":"","score":40},
                {"community":"1","point":"Orders are handled.","score":"35"}]}""",
                "{\"points\": []}");

        assertFalse(off.mapsCommunities());
        assertEquals(List.of(), off.mapCommunities("q", batch));
        assertTrue(off.requests.isEmpty());
        assertTrue(on.mapsCommunities());
        assertEquals(List.of(new CommunityPoint("c-2", "Payments are charged.", 100),
                new CommunityPoint("c-1", "Orders are handled.", 35)), on.mapCommunities("How does it work?", batch));
        assertEquals(List.of(), on.mapCommunities("q", batch));
        PromptedLlmPort.CompletionRequest request = on.requests.getFirst();
        assertEquals(PromptedLlmPort.Purpose.COMMUNITY_POINTS, request.purpose());
        assertEquals(PromptedLlmPort.Schemas.COMMUNITY_POINTS, request.jsonSchema());
        assertTrue(request.lastUserText().contains("[1] Orders: Order handling.\n[2] Payments: Charging."));
        assertTrue(request.lastUserText().endsWith("Question: How does it work?"));
    }

    @Test
    void aMapReplyWithoutPointsIsAskedAgain() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withSynthesis(true),
                "{\"answer\": \"Orders.\"}", "{\"points\": []}");

        assertEquals(List.of(), port.mapCommunities("q", List.of(new Community("c-1", "Orders."))));
        assertEquals(2, port.requests.size());
    }

    @Test
    void withoutGleaningsExtractionIsOneCall() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true),
                "{\"entities\":[{\"name\":\"Ada\",\"type\":\"Person\"}],\"relationships\":[]}",
                "{\"entities\":[{\"name\":\"Bob\",\"type\":\"Person\"}],\"relationships\":[]}");

        GraphExtraction extraction = port.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."),
                List.of("Person", "Other"));

        assertEquals(List.of("Ada"), extraction.entities().stream().map(Entity::name).toList());
        assertEquals(1, port.requests.size());
    }

    @Test
    void gleaningAddsWhatTheFirstPassMissedInTheSameConversation() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true).withGleanings(2),
                "{\"entities\":[{\"name\":\"Ada\",\"type\":\"Person\",\"description\":\"A mathematician.\"}],"
                        + "\"relationships\":[]}",
                "{\"entities\":[{\"name\":\"Bob\",\"type\":\"Person\"},"
                        + "{\"name\":\"Ada\",\"type\":\"Person\",\"description\":\"Met Bob.\"}],"
                        + "\"relationships\":[{\"source\":\"Ada\",\"sourceType\":\"Person\",\"type\":\"met\","
                        + "\"target\":\"Bob\",\"targetType\":\"Person\"}]}",
                "{\"entities\":[],\"relationships\":[]}");

        GraphExtraction extraction = port.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."),
                List.of("Person", "Other"));

        assertEquals(List.of("Ada", "Bob"), extraction.entities().stream().map(Entity::name).toList());
        assertEquals("A mathematician. Met Bob.", extraction.entities().getFirst().description());
        assertEquals(1, extraction.relationships().size());
        assertEquals(3, port.requests.size());
        List<PromptedLlmPort.CompletionRequest.Message> second = port.requests.get(1).messages();
        assertEquals(List.of(PromptedLlmPort.Role.USER, PromptedLlmPort.Role.ASSISTANT, PromptedLlmPort.Role.USER),
                second.stream().map(PromptedLlmPort.CompletionRequest.Message::role).toList());
        assertTrue(second.get(1).text().contains("A mathematician."));
        assertEquals(PromptedLlmPort.GLEANING_PROMPT, second.get(2).text());
        assertEquals(PromptedLlmPort.Purpose.EXTRACTION, port.requests.get(1).purpose());
        assertEquals(5, port.requests.get(2).messages().size());
    }

    @Test
    void gleaningStopsWhenATurnAddsNothingNew() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true).withGleanings(3),
                "{\"entities\":[{\"name\":\"Ada\",\"type\":\"Person\"}],\"relationships\":[]}",
                "{\"entities\":[{\"name\":\"Ada\",\"type\":\"Person\"}],\"relationships\":[]}",
                "{\"entities\":[{\"name\":\"Bob\",\"type\":\"Person\"}],\"relationships\":[]}");

        GraphExtraction extraction = port.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."),
                List.of("Person", "Other"));

        assertEquals(List.of("Ada"), extraction.entities().stream().map(Entity::name).toList());
        assertEquals(2, port.requests.size());
    }

    @Test
    void anUnusableGleaningReplyKeepsTheFirstPass() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withExtraction(true).withGleanings(1),
                "{\"entities\":[{\"name\":\"Ada\",\"type\":\"Person\"}],\"relationships\":[]}",
                "Nothing else, sorry.");

        GraphExtraction extraction = port.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."),
                List.of("Person", "Other"));

        assertEquals(List.of("Ada"), extraction.entities().stream().map(Entity::name).toList());
        assertEquals(2, port.requests.size());
    }

    @Test
    void negativeGleaningsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PromptedLlmPort.Options.defaults().withGleanings(-1));
        assertEquals(0, PromptedLlmPort.Options.defaults().gleanings());
        assertEquals(0, new PromptedLlmPort.Options(true, true, true, 1, 1, 1, 1).gleanings());
    }

    @Test
    void summaryPromptIncludesRelationshipDescriptions() {
        ScriptedPort port = new ScriptedPort("{\"title\":\"Orders\",\"summary\":\"About orders.\"}");

        port.summarizeCommunity(MEMBERS, List.of(new Relationship("OrderService", "Class", "CALLS", "OrderRepository",
                "Interface", "Saves each placed order.", List.of(), 1)));

        assertTrue(port.requests.getFirst().lastUserText()
                .contains("1. OrderService -[CALLS]-> OrderRepository: Saves each placed order."));
    }

    @Test
    void answerPromptKeepsBackgroundItemsUncited() {
        ScriptedPort port = new ScriptedPort(PromptedLlmPort.Options.defaults().withSynthesis(true),
                "{\"answer\": \"It saves orders [2].\", \"notInContext\": false}");

        port.synthesizeAnswer("What does it do?", List.of(
                new ContextItem(1, RetrievalStep.Kind.ENTITY, "OrderService (Class)", null),
                new ContextItem(2, RetrievalStep.Kind.TEXT_UNIT, "save(order)", "t-1")));

        String prompt = port.requests.getFirst().lastUserText();
        assertTrue(prompt.contains("[1] Entity: OrderService (Class)"));
        assertTrue(prompt.contains("background facts: use them, but never cite them"));
        assertTrue(prompt.contains("answers only part of the question"));
        assertTrue(prompt.endsWith("Question: What does it do?"));
    }

    @Test
    void writesAComparisonVerdictFromTheFacts() {
        ScriptedPort port = new ScriptedPort("{\"verdict\": \"GraphRAG read two more documents.\"}");
        ComparisonFacts facts = new ComparisonFacts("global", new ComparisonStats(6, 3, 120),
                new ComparisonStats(5, 1, 40), 5, 2);

        ComparisonVerdict verdict = port.compareAnswers("Who?", "Ada [1].", "Bob [1].", facts);

        assertEquals(new ComparisonVerdict("GraphRAG read two more documents.", ComparisonVerdict.Source.LLM), verdict);
        PromptedLlmPort.CompletionRequest request = port.requests.getFirst();
        assertEquals(PromptedLlmPort.Purpose.VERDICT, request.purpose());
        assertEquals(PromptedLlmPort.Schemas.VERDICT, request.jsonSchema());
        assertTrue(request.lastUserText().contains("GraphRAG (GLOBAL search)"));
        assertTrue(request.lastUserText().contains("- GraphRAG: 6 context items from 3 distinct documents, 120 ms"));
        assertTrue(request.lastUserText().contains("2 of the 5 passages"));
        assertTrue(request.lastUserText().contains("GraphRAG answer:\nAda [1]."));
    }

    @Test
    void anEmptyVerdictFailsSoTheCallerFallsBackToTheRule() {
        ScriptedPort port = new ScriptedPort("{\"verdict\": \"\"}", "{}");

        assertEquals(PromptedLlmPort.Purpose.VERDICT, assertThrows(LlmReplyException.class,
                () -> port.compareAnswers("q", "a", "b", null)).purpose());
    }

    @Test
    void summariesAndSubQuestionsAreModelBacked() {
        ScriptedPort port = new ScriptedPort();

        assertTrue(port.summarizesCommunities());
        assertTrue(port.derivesSubQuestions());
    }
}
