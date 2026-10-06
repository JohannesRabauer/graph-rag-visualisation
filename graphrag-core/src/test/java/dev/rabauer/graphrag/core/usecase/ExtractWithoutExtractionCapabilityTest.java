package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractWithoutExtractionCapabilityTest {

    private static final Corpus CORPUS = new Corpus("c-1",
            List.of(new UploadedDocument("story.txt", "Ada Lovelace met Charles Babbage in London.")));

    /** Reports no extraction capability; any extraction call fails the test. */
    private static final class NonExtractingPort implements LlmPort {
        @Override
        public GraphExtraction extract(Corpus corpus) {
            throw new AssertionError("no extraction call expected");
        }

        @Override
        public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
            throw new AssertionError("no extraction call expected");
        }

        @Override
        public boolean extractsEntities() {
            return false;
        }
    }

    @Test
    void aPortWithoutTheCapabilityIsNeverAskedAndOnlyTextUnitsArePersisted() {
        TestGraphStore store = new TestGraphStore();

        new ExtractEntitiesAndRelationships(new NonExtractingPort(), store).run(CORPUS);

        assertEquals(1, store.textUnits("c-1").size());
        assertTrue(store.entities("c-1").isEmpty());
        assertTrue(store.relationships("c-1").isEmpty());
    }

    @Test
    void aNullPortOrNoneBehavesLikeAPortWithoutTheCapability() {
        TestGraphStore withNull = new TestGraphStore();
        TestGraphStore withNone = new TestGraphStore();

        new ExtractEntitiesAndRelationships(null, withNull).run(CORPUS);
        GraphExtraction extracted = new ExtractEntitiesAndRelationships(LlmPort.none(), withNone).extract(CORPUS);

        assertEquals(1, withNull.textUnits("c-1").size());
        assertTrue(withNull.entities("c-1").isEmpty());
        assertEquals(GraphExtraction.empty(), extracted);
    }
}
