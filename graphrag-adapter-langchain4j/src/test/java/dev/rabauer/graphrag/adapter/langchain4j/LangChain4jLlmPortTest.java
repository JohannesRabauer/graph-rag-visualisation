package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.usecase.EntityTypes;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jLlmPortTest {

    @Test
    void extractsEntitiesAndRelationshipsFromCorpusText() {
        LangChain4jLlmPort port = new LangChain4jLlmPort();
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("sherlock.txt", "Sherlock Holmes met Dr. Watson. Mary Morstan helped Sherlock Holmes.")));

        var extraction = port.extract(corpus);

        assertTrue(extraction.entities().size() >= 3);
        assertTrue(extraction.relationships().stream().anyMatch(r -> r.type().equals("met") || r.type().equals("related_to") || r.type().equals("helped")));
    }

    @Test
    void extractsFromASingleTextUnitsTextOnly() {
        LangChain4jLlmPort port = new LangChain4jLlmPort();
        TextUnit unit = new TextUnit("c1::doc-0::tu-1", "c1", "sherlock.txt", 1,
                "Sherlock Holmes met Dr. Watson. Mary Morstan helped Sherlock Holmes.");

        var extraction = port.extract(unit, EntityTypes.ALL);

        assertTrue(extraction.entities().stream()
                .anyMatch(entity -> entity.name().equals("Sherlock Holmes") && entity.type().equals("Person")));
        assertTrue(extraction.entities().stream()
                .anyMatch(entity -> entity.name().equals("Mary Morstan") && entity.type().equals("Person")));
        assertTrue(extraction.relationships().stream().anyMatch(r -> r.type().equals("helped")));
        assertTrue(extraction.entities().stream().allMatch(entity -> !entity.description().isBlank()));
        assertTrue(extraction.relationships().stream().allMatch(relationship -> !relationship.description().isBlank()));
        assertEquals(extraction, port.extract(unit, EntityTypes.ALL), "the offline stub must stay deterministic");
        assertTrue(port.extract(new TextUnit("c1::doc-0::tu-2", "c1", "x.txt", 2, "  "), EntityTypes.ALL)
                .entities().isEmpty());
    }

    @Test
    void offlineStubReturnsTheSameDeterministicTitleAndSummaryTwice() {
        LangChain4jLlmPort port = new LangChain4jLlmPort();
        List<Entity> members = List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person"),
                new Entity("Baker Street", "Concept"));
        List<Relationship> relationships = List.of(
                new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person"));

        CommunitySummary first = port.summarizeCommunity(members, relationships);
        CommunitySummary second = port.summarizeCommunity(members, relationships);

        assertEquals(first, second);
        assertEquals("Sherlock Holmes & Dr. Watson", first.title());
        assertEquals("This community centers on Sherlock Holmes, Dr. Watson, Baker Street.", first.summary());
    }
}
