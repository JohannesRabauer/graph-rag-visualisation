package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Batched embedding calls and the text embedded for a Community. */
class EmbedGraphElementsBatchTest {

    private static final String CORPUS = "repo";

    /** A model with a batch call: records the batches, embeds a text as its length. */
    private static final class BatchPort implements EmbeddingPort {
        final List<List<String>> batches = new ArrayList<>();
        final List<String> singles = new ArrayList<>();

        @Override
        public float[] embed(String text) {
            singles.add(text);
            return new float[] {text.length()};
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            batches.add(List.copyOf(texts));
            return texts.stream().map(text -> new float[] {text.length()}).toList();
        }
    }

    private static TestGraphStore store(int entities, int communities) {
        TestGraphStore store = new TestGraphStore();
        for (int i = 1; i <= entities; i++) {
            store.persistEntities(CORPUS, List.of(new Entity("E" + i, "Class", "d" + "x".repeat(i), List.of())));
        }
        for (int i = 1; i <= communities; i++) {
            store.persistCommunities(CORPUS, List.of(new Community("c" + i, "T" + i, "summary " + "y".repeat(i))));
        }
        return store;
    }

    @Test
    void theDefaultEmbedAllEmbedsOneByOneInOrder() {
        List<String> seen = new ArrayList<>();
        EmbeddingPort port = text -> {
            seen.add(text);
            return new float[] {text.length()};
        };

        List<float[]> vectors = port.embedAll(List.of("a", "bbb", "cc"));

        assertEquals(List.of("a", "bbb", "cc"), seen);
        assertEquals(3, vectors.size());
        assertArrayEquals(new float[] {3}, vectors.get(1));
        assertEquals(List.of(), port.embedAll(List.of()));
    }

    @Test
    void embedsInBatchesOfTheConfiguredSize() {
        TestGraphStore store = store(5, 3);
        BatchPort port = new BatchPort();

        new EmbedGraphElements(store, port, EmbedGraphElements.Options.defaults().withBatchSize(2)).run(CORPUS);

        assertEquals(List.of(2, 2, 1, 2, 1), port.batches.stream().map(List::size).toList());
        assertTrue(port.singles.isEmpty(), "no single call when a batch call exists");
        // Every vector went to its own element.
        assertArrayEquals(new float[] {"E3: dxxx".length()}, store.entityEmbeddings.get(CORPUS).get("e3::class"));
        assertArrayEquals(new float[] {"summary yy".length()}, store.communityEmbeddings.get(CORPUS).get("c2"));
        assertEquals(5, store.entityEmbeddings.get(CORPUS).size());
        assertEquals(3, store.communityEmbeddings.get(CORPUS).size());
    }

    @Test
    void theDefaultBatchSizeSendsEverythingInFewCalls() {
        TestGraphStore store = store(40, 3);
        BatchPort port = new BatchPort();

        new EmbedGraphElements(store, port).run(CORPUS);

        assertEquals(List.of(EmbedGraphElements.Options.DEFAULT_BATCH_SIZE, 40 - EmbedGraphElements.Options.DEFAULT_BATCH_SIZE, 3),
                port.batches.stream().map(List::size).toList());
    }

    @Test
    void embedEntitiesAndEmbedCommunitiesBatchToo() {
        TestGraphStore store = store(3, 3);
        BatchPort port = new BatchPort();
        EmbedGraphElements embedder = new EmbedGraphElements(store, port,
                EmbedGraphElements.Options.defaults().withBatchSize(2));

        embedder.embedEntities(CORPUS, store.entities(CORPUS));
        embedder.embedCommunities(CORPUS);

        assertEquals(List.of(2, 1, 2, 1), port.batches.stream().map(List::size).toList());
    }

    @Test
    void aPortThatReturnsTheWrongNumberOfVectorsIsRejected() {
        TestGraphStore store = store(3, 0);
        EmbeddingPort broken = new EmbeddingPort() {
            @Override
            public float[] embed(String text) {
                return new float[] {1};
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                return List.of(new float[] {1});
            }
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new EmbedGraphElements(store, broken).run(CORPUS));

        assertTrue(thrown.getMessage().contains("3 texts") && thrown.getMessage().contains("1 vector"),
                thrown.getMessage());
        assertTrue(store.entityEmbeddings.isEmpty());
    }

    // -- What is embedded for a Community ----------------------------------------------------

    private static TestGraphStore withMembers() {
        TestGraphStore store = new TestGraphStore();
        store.persistEntities(CORPUS, List.of(new Entity("A", "Class"), new Entity("B", "Class"),
                new Entity("C", "Class"), new Entity("D", "Class"), new Entity("Outside", "Class")));
        store.persistRelationships(CORPUS, List.of(
                new Relationship("A", "Class", "CALLS", "B", "Class", "", List.of(), 5),
                new Relationship("A", "Class", "CALLS", "C", "Class", "", List.of(), 3),
                new Relationship("B", "Class", "CALLS", "C", "Class", "", List.of(), 1),
                new Relationship("D", "Class", "CALLS", "Outside", "Class", "", List.of(), 9)));
        store.persistCommunities(CORPUS, List.of(new Community("c1", "Brokers", "Handles topics.")));
        store.persistCommunityMemberships(CORPUS, List.of(
                new CommunityMembership("c1", "d::class"), new CommunityMembership("c1", "c::class"),
                new CommunityMembership("c1", "b::class"), new CommunityMembership("c1", "a::class")));
        return store;
    }

    private static String embeddedCommunityText(EmbedGraphElements.Options options) {
        BatchPort port = new BatchPort();
        new EmbedGraphElements(withMembers(), port, options).embedCommunities(CORPUS);
        return port.batches.getFirst().getFirst();
    }

    @Test
    void aCommunityIsEmbeddedAsItsSummaryByDefault() {
        assertEquals("Handles topics.", embeddedCommunityText(EmbedGraphElements.Options.defaults()));
    }

    @Test
    void theTitleCanBeEmbeddedToo() {
        assertEquals("Brokers: Handles topics.",
                embeddedCommunityText(EmbedGraphElements.Options.defaults().withCommunityTitle(true)));
    }

    @Test
    void theKeyMembersCanBeEmbeddedToo() {
        // Most connected inside the Community first: A (5+3), B (5+1), C (3+1), D (none).
        assertEquals("Brokers: Handles topics.\nKey members: A, B",
                embeddedCommunityText(EmbedGraphElements.Options.defaults().withCommunityTitle(true)
                        .withCommunityKeyMembers(2)));
        assertEquals("Handles topics.\nKey members: A, B, C, D",
                embeddedCommunityText(EmbedGraphElements.Options.defaults().withCommunityKeyMembers(10)));
    }

    @Test
    void theOptionsValidateAndKeepTheirDefaults() {
        EmbedGraphElements.Options defaults = EmbedGraphElements.Options.defaults();

        assertEquals(32, defaults.batchSize());
        assertEquals(false, defaults.communityTitle());
        assertEquals(0, defaults.communityKeyMembers());
        assertThrows(IllegalArgumentException.class, () -> defaults.withBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withCommunityKeyMembers(-1));
    }
}
