package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.EntityTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of an {@link LlmPort}: what the port promises for each of its
 * optional capability flags ({@link LlmPort#extractsEntities()},
 * {@link LlmPort#summarizesCommunities()}, {@link LlmPort#derivesSubQuestions()},
 * {@link LlmPort#synthesizesAnswers()}, {@link LlmPort#mapsCommunities()},
 * {@link LlmPort#summarizesDescriptions()}), taken from the port's Javadoc and
 * the "Optional LLM capabilities" section of the core README. A port with a
 * capability off is held to the documented deterministic fallback, a port with
 * it on to a usable result. Extend it and return the port from {@link #port()};
 * the port must need no network and no API key here.
 */
public abstract class LlmPortContract {

    private static final String TEXT = "Sherlock Holmes met Dr. Watson. Mary Morstan helped Sherlock Holmes.";

    /** The port under test. */
    protected abstract LlmPort port();

    private static Corpus corpus() {
        return new Corpus("contract", List.of(new UploadedDocument("sherlock.txt", TEXT)));
    }

    private static TextUnit unit() {
        return new TextUnit("contract::doc-0::tu-0", "contract", "sherlock.txt", 0, TEXT);
    }

    private static List<Entity> members() {
        return List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person"));
    }

    private static List<Relationship> relationships() {
        return List.of(new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person"));
    }

    private static void assertWellFormed(GraphExtraction extraction, String what) {
        assertNotNull(extraction, what + " returned null");
        assertNotNull(extraction.entities(), what + ": entities null");
        assertNotNull(extraction.relationships(), what + ": relationships null");
    }

    // --- extractsEntities ---

    @Test
    void extractionIsNeverNullForACorpusOrATextUnit() {
        LlmPort port = port();
        assertWellFormed(port.extract(corpus()), "extract(Corpus)");
        assertWellFormed(port.extract(unit(), EntityTypes.ALL), "extract(TextUnit, types)");
        assertWellFormed(port.extract(unit(), EntityTypes.ALL, List.of("Sherlock Holmes")),
                "extract(TextUnit, types, known names)");
    }

    @Test
    void aPortThatDoesNotExtractReturnsTheEmptyExtraction() {
        LlmPort port = port();
        if (port.extractsEntities()) {
            return;
        }
        assertEquals(GraphExtraction.empty(), port.extract(corpus()));
        assertEquals(GraphExtraction.empty(), port.extract(unit(), EntityTypes.ALL));
        assertEquals(GraphExtraction.empty(), port.extract(unit(), EntityTypes.ALL, List.of()));
    }

    @Test
    void aPortThatExtractsFindsEntitiesInClearText() {
        LlmPort port = port();
        if (!port.extractsEntities()) {
            return;
        }
        assertFalse(port.extract(corpus()).entities().isEmpty(), "no entities extracted from the corpus");
        assertFalse(port.extract(unit(), EntityTypes.ALL).entities().isEmpty(),
                "no entities extracted from the text unit");
    }

    // --- summarizesCommunities ---

    @Test
    void communitySummariesAreNeverNull() {
        LlmPort port = port();
        assertNotNull(port.summarizeCommunity(members()), "summarizeCommunity(members)");
        assertNotNull(port.summarizeCommunity(List.of()), "summarizeCommunity(no members)");
        assertNotNull(port.summarizeCommunity(members(), relationships()), "summarizeCommunity(members, relationships)");
        assertNotNull(port.summarizeCommunity(List.of(), List.of()), "summarizeCommunity of an empty Community");
    }

    @Test
    void aSummarizingPortWritesANonBlankTitleAndSummary() {
        LlmPort port = port();
        if (!port.summarizesCommunities()) {
            return;
        }
        CommunitySummary summary = port.summarizeCommunity(members(), relationships());

        assertFalse(summary.title().isBlank(), "blank title");
        assertFalse(summary.summary().isBlank(), "blank summary");
    }

    @Test
    void aPortWithoutSummariesKeepsTheDeterministicOnesForAnyCommunity() {
        LlmPort port = port();
        if (port.summarizesCommunities()) {
            return;
        }
        assertFalse(port.summarizeCommunity(members()).isBlank(), "blank deterministic summary");
        assertFalse(port.summarizeCommunity(List.of()).isBlank(), "blank summary of an empty Community");
        assertEquals(port.summarizeCommunity(members()), port.summarizeCommunity(members()),
                "the deterministic summary must not change between calls");
    }

    // --- derivesSubQuestions ---

    @Test
    void driftSubQuestionsAreOnePerCandidateCommunity() {
        LlmPort port = port();
        List<Community> communities = List.of(new Community("c-1", "Order handling."),
                new Community("c-2", "Payment processing."));

        List<String> subQuestions = port.deriveDriftSubQuestions("Who saves orders?", communities);

        assertNotNull(subQuestions);
        assertEquals(communities.size(), subQuestions.size(), "one sub-question per candidate Community");
        assertTrue(port.deriveDriftSubQuestions("Who saves orders?", List.of()).isEmpty(),
                "no candidates, no sub-questions");
    }

    @Test
    void aPortWithoutSubQuestionDerivationUsesTheDeterministicOnes() {
        LlmPort port = port();
        if (port.derivesSubQuestions()) {
            return;
        }
        assertEquals(List.of("Who saves orders? Community summary: Order handling.",
                        "Who saves orders? Community summary: Payment processing."),
                port.deriveDriftSubQuestions("Who saves orders?",
                        List.of(new Community("c-1", "Order handling."), new Community("c-2", "Payment processing."))));
    }

    // --- synthesizesAnswers ---

    @Test
    void aSynthesizingPortAnswersFromANumberedContext() {
        LlmPort port = port();
        if (!port.synthesizesAnswers()) {
            return;
        }
        List<ContextItem> context = List.of(
                new ContextItem(1, RetrievalStep.Kind.TEXT_UNIT, TEXT, "contract::doc-0::tu-0"));

        SynthesizedAnswer answer = port.synthesizeAnswer("Who helped Sherlock Holmes?", context);

        assertNotNull(answer, "a port that synthesizes answers must return one");
        assertNotNull(answer.text(), "answer text");
    }

    @Test
    void chunkSynthesisAndTheComparisonVerdictAreNeverNull() {
        LlmPort port = port();
        List<Chunk> chunks = List.of(new Chunk("c-1", "contract", 0, TEXT));

        assertNotNull(port.synthesizeFromChunks("Who helped Sherlock Holmes?", chunks), "synthesizeFromChunks");
        assertNotNull(port.synthesizeFromChunks("Who helped Sherlock Holmes?", List.of()),
                "synthesizeFromChunks(no chunks)");
        ComparisonVerdict verdict = port.compareAnswers("q", "graph answer", "vector answer",
                new ComparisonFacts(null, null, null, 0, 0));
        assertNotNull(verdict, "compareAnswers");
        assertNotNull(verdict.source(), "verdict source");
    }

    // --- mapsCommunities / summarizesDescriptions ---

    @Test
    void mapCommunitiesIsEmptyWhenTheCapabilityIsOff() {
        LlmPort port = port();
        if (port.mapsCommunities()) {
            return;
        }
        var points = port.mapCommunities("Who saves orders?", List.of(new Community("c-1", "Order handling.")));

        assertNotNull(points);
        assertTrue(points.isEmpty(), "a port that does not map Communities returns no points");
    }

    @Test
    void descriptionSummariesAreNeverNullAndUnchangedWhenTheCapabilityIsOff() {
        LlmPort port = port();
        String summary = port.summarizeDescription("Sherlock Holmes", "A detective. A consulting detective.");

        assertNotNull(summary);
        if (!port.summarizesDescriptions()) {
            assertEquals("A detective. A consulting detective.", summary);
        }
    }
}
