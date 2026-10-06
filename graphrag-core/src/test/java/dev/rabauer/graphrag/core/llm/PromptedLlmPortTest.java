package dev.rabauer.graphrag.core.llm;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
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
        List<PromptedLlmPort.Message> retry = port.requests.get(1).messages();
        assertEquals(List.of(PromptedLlmPort.Role.USER, PromptedLlmPort.Role.ASSISTANT, PromptedLlmPort.Role.USER),
                retry.stream().map(PromptedLlmPort.Message::role).toList());
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
    void summariesAndSubQuestionsAreModelBacked() {
        ScriptedPort port = new ScriptedPort();

        assertTrue(port.summarizesCommunities());
        assertTrue(port.derivesSubQuestions());
    }
}
