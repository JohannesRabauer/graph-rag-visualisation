package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPortCapabilitiesTest {

    @Test
    void aLambdaPortExtractsAndHasEveryOtherCapabilityOff() {
        LlmPort lambda = corpus -> GraphExtraction.empty();

        assertTrue(lambda.extractsEntities());
        assertFalse(lambda.summarizesCommunities());
        assertFalse(lambda.derivesSubQuestions());
        assertFalse(lambda.synthesizesAnswers());
    }

    @Test
    void noneHasEveryCapabilityOffAndNeverExtracts() {
        LlmPort none = LlmPort.none();

        assertSame(none, LlmPort.none());
        assertFalse(none.extractsEntities());
        assertFalse(none.summarizesCommunities());
        assertFalse(none.derivesSubQuestions());
        assertFalse(none.synthesizesAnswers());
        assertEquals(GraphExtraction.empty(),
                none.extract(new Corpus("c", List.of(new UploadedDocument("a.txt", "Ada met Bob.")))));
        assertEquals(GraphExtraction.empty(), none.extract(new TextUnit("t", "c", "a.txt", 0, "Ada met Bob."), List.of()));
        assertNull(none.synthesizeAnswer("q", List.of()));
    }

    @Test
    void noneKeepsTheDeterministicSummariesAndSubQuestions() {
        LlmPort none = LlmPort.none();
        List<Entity> members = List.of(new Entity("OrderService", "Class"), new Entity("OrderRepository", "Interface"));

        assertEquals("This community centers on OrderService, OrderRepository.", none.summarizeCommunity(members));
        assertEquals("OrderService & OrderRepository", none.summarizeCommunity(members, List.of()).title());
        assertEquals(List.of("Who saves orders? Community summary: Order handling."),
                none.deriveDriftSubQuestions("Who saves orders?", List.of(new Community("c-1", "Order handling."))));
    }
}
