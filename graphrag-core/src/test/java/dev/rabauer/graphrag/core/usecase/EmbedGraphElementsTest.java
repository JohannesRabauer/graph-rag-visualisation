package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedGraphElementsTest {

    private static final Corpus CORPUS = new Corpus("corpus-a", List.of());

    private static FakeGraphStore store() {
        return new FakeGraphStore()
                .entities("corpus-a",
                        new Entity("Ada Lovelace", "Person", "wrote the first computer program", List.of()),
                        new Entity("Analytical Engine", "Machine", "", List.of()))
                .communities("corpus-a", new Community("community-1", "Ada and the engine"))
                .entities("corpus-b", new Entity("Other", "Person", "elsewhere", List.of()))
                .communities("corpus-b", new Community("community-1", "Another corpus"));
    }

    @Test
    void embedsEveryEntityAndCommunityOfTheCorpusFromTheirPersistedText() {
        FakeGraphStore store = store();
        FakeEmbeddingPort port = new FakeEmbeddingPort()
                .map("Ada Lovelace: wrote the first computer program", 1, 0, 0, 0)
                .map("Ada and the engine", 0, 1, 0, 0);

        new EmbedGraphElements(store, port).run(CORPUS);

        assertEquals(List.of("Ada Lovelace: wrote the first computer program", "Analytical Engine: ",
                "Ada and the engine"), port.embedded);
        assertEquals(Set.of("ada lovelace::person", "analytical engine::machine"),
                store.entityEmbeddingsByCorpus.get("corpus-a").keySet());
        assertArrayEquals(new float[] {1, 0, 0, 0},
                store.entityEmbeddingsByCorpus.get("corpus-a").get("ada lovelace::person"));
        assertArrayEquals(new float[] {0, 1, 0, 0},
                store.communityEmbeddingsByCorpus.get("corpus-a").get("community-1"));
        assertFalse(store.entityEmbeddingsByCorpus.containsKey("corpus-b"));
        assertFalse(store.communityEmbeddingsByCorpus.containsKey("corpus-b"));
    }

    @Test
    void embedsNothingWithANonSemanticPort() {
        FakeGraphStore store = store();
        FakeEmbeddingPort port = new FakeEmbeddingPort(false);

        new EmbedGraphElements(store, port).run(CORPUS);

        assertTrue(port.embedded.isEmpty());
        assertTrue(store.entityEmbeddingsByCorpus.isEmpty());
        assertTrue(store.communityEmbeddingsByCorpus.isEmpty());
    }

    @Test
    void embedsNothingWithoutAPort() {
        FakeGraphStore store = store();

        new EmbedGraphElements(store, null).run(CORPUS);

        assertTrue(store.entityEmbeddingsByCorpus.isEmpty());
        assertTrue(store.communityEmbeddingsByCorpus.isEmpty());
    }

    @Test
    void anEmbeddingFailurePropagatesWithoutRetryingOrPersisting() {
        FakeGraphStore store = store();
        IllegalStateException outage = new IllegalStateException("embedding outage");
        int[] calls = {0};
        EmbeddingPort failing = text -> {
            calls[0]++;
            throw outage;
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new EmbedGraphElements(store, failing).run(CORPUS));

        assertSame(outage, thrown);
        assertEquals(1, calls[0]);
        assertTrue(store.entityEmbeddingsByCorpus.isEmpty());
    }
}
